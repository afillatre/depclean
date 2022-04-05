package se.kth.depclean.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.logging.Log;
import org.jetbrains.annotations.Nullable;
import se.kth.depclean.core.analysis.AnalysisFailureException;
import se.kth.depclean.core.analysis.DefaultProjectDependencyAnalyzer;
import se.kth.depclean.core.analysis.ProjectDependencyAnalyzerException;
import se.kth.depclean.core.analysis.graph.DependencyGraph;
import se.kth.depclean.core.analysis.model.ProjectDependencyAnalysis;
import se.kth.depclean.core.model.ClassName;
import se.kth.depclean.core.model.Dependency;
import se.kth.depclean.core.model.ProjectContext;
import se.kth.depclean.core.model.Scope;
import se.kth.depclean.core.wrapper.DependencyManagerWrapper;

/**
 * Runs the depclean process, regardless of a specific dependency manager.
 */
@AllArgsConstructor
public class DepCleanManager {

  private static final String SEPARATOR = "-------------------------------------------------------";

  private final DependencyManagerWrapper dependencyManager;
  private final boolean skipDepClean;
  private final boolean ignoreTests;
  private final Set<String> ignoreScopes;
  private final Set<String> ignoreDependencies;
  private final boolean failIfUnusedDirect;
  private final boolean failIfUnusedTransitive;
  private final boolean failIfUnusedInherited;
  private final boolean createPomDebloated;
  private final boolean createResultJson;
  private final boolean createCallGraphCsv;

  /**
   * Execute the depClean manager.
   */
  @SneakyThrows
  public void execute() throws AnalysisFailureException {
    final long startTime = System.currentTimeMillis();

    if (skipDepClean) {
      getLog().info("Skipping DepClean plugin execution");
      return;
    }
    printString(SEPARATOR);
    getLog().info("Starting DepClean dependency analysis");

    if (dependencyManager.isMaven() && dependencyManager.isPackagingPom()) {
      getLog().info("Skipping because packaging type is pom.");
      return;
    }

    dependencyManager.copyAndExtractDependencies();

    final ProjectDependencyAnalysis analysis = getAnalysis();
    if (analysis == null) {
      return;
    }
    analysis.print();

    /* Fail the build if there are unused direct dependencies */
    if (failIfUnusedDirect && analysis.hasUnusedDirectDependencies()) {
      throw new AnalysisFailureException(
          "Build failed due to unused direct dependencies in the dependency tree of the project.");
    }

    /* Fail the build if there are unused transitive dependencies */
    if (failIfUnusedTransitive && analysis.hasUnusedTransitiveDependencies()) {
      throw new AnalysisFailureException(
          "Build failed due to unused transitive dependencies in the dependency tree of the project.");
    }

    /* Fail the build if there are unused inherited dependencies */
    if (failIfUnusedInherited && analysis.hasUnusedInheritedDependencies()) {
      throw new AnalysisFailureException(
          "Build failed due to unused inherited dependencies in the dependency tree of the project.");
    }

    /* Writing the debloated version of the pom */
    if (createPomDebloated) {
      dependencyManager.getDebloater(analysis).write();
    }

    /* Writing the JSON file with the depclean results */
    if (createResultJson) {
      createResultJson(analysis);
    }

    final long stopTime = System.currentTimeMillis();
    getLog().info("Analysis done in " + getTime(stopTime - startTime));
  }

  private void createResultJson(ProjectDependencyAnalysis analysis) {
    printString("Creating depclean-results.json, please wait...");
    final File jsonFile = new File(dependencyManager.getBuildDirectory() + File.separator + "depclean-results.json");
    final File treeFile = new File(dependencyManager.getBuildDirectory() + File.separator + "tree.txt");
    final File csvFile = new File(dependencyManager.getBuildDirectory() + File.separator + "depclean-callgraph.csv");
    try {
      dependencyManager.generateDependencyTree(treeFile);
    } catch (IOException | InterruptedException e) {
      getLog().error("Unable to generate dependency tree.");
      // Restore interrupted state...
      Thread.currentThread().interrupt();
      return;
    }
    if (createCallGraphCsv) {
      printString("Creating " + csvFile.getName() + ", please wait...");
      try {
        FileUtils.write(csvFile, "OriginClass,TargetClass,OriginDependency,TargetDependency\n", Charset.defaultCharset());
      } catch (IOException e) {
        getLog().error("Error writing the CSV header.");
      }
    }
    String treeAsJson = dependencyManager.getTreeAsJson(
        treeFile,
        analysis,
        csvFile,
        createCallGraphCsv
    );

    try {
      FileUtils.write(jsonFile, treeAsJson, Charset.defaultCharset());
    } catch (IOException e) {
      getLog().error("Unable to generate " + jsonFile.getName() + " file.");
    }
    if (jsonFile.exists()) {
      getLog().info(jsonFile.getName() + " file created in: " + jsonFile.getAbsolutePath());
    }
    if (csvFile.exists()) {
      getLog().info(csvFile.getName() + " file created in: " + csvFile.getAbsolutePath());
    }
  }

  @Nullable
  private ProjectDependencyAnalysis getAnalysis() {
    /* Analyze dependencies usage status */
    final ProjectContext projectContext = buildProjectContext();
    final ProjectDependencyAnalysis analysis;
    final DefaultProjectDependencyAnalyzer dependencyAnalyzer = new DefaultProjectDependencyAnalyzer(projectContext);
    try {
      analysis = dependencyAnalyzer.analyze();
    } catch (ProjectDependencyAnalyzerException e) {
      getLog().error("Unable to analyze dependencies.");
      return null;
    }
    return analysis;
  }

  private ProjectContext buildProjectContext() {
    if (ignoreTests) {
      ignoreScopes.add("test");
    }

    // Consider are used all the classes declared in Maven processors
    Set<ClassName> allUsedClasses = new HashSet<>();
    Set<ClassName> usedClassesFromProcessors = dependencyManager
        .collectUsedClassesFromProcessors().stream()
        .map(ClassName::new)
        .collect(Collectors.toSet());

    // Consider as used all the classes located in the imports of the source code
    Set<ClassName> usedClassesFromSource = dependencyManager.collectUsedClassesFromSource(
            dependencyManager.getSourceDirectory(),
            dependencyManager.getTestDirectory())
        .stream()
        .map(ClassName::new)
        .collect(Collectors.toSet());

    allUsedClasses.addAll(usedClassesFromProcessors);
    allUsedClasses.addAll(usedClassesFromSource);

    final DependencyGraph dependencyGraph = dependencyManager.dependencyGraph();
    return new ProjectContext(
        dependencyGraph,
        dependencyManager.getOutputDirectory(),
        dependencyManager.getTestOutputDirectory(),
        dependencyManager.getSourceDirectory(),
        dependencyManager.getTestDirectory(),
        dependencyManager.getDependenciesDirectory(),
        ignoreScopes.stream().map(Scope::new).collect(Collectors.toSet()),
        toDependency(dependencyGraph.allDependencies(), ignoreDependencies),
        allUsedClasses
    );
  }

  /**
   * Returns a set of {@code DependencyCoordinate}s that match given string representations.
   *
   * @param allDependencies    all known dependencies
   * @param ignoreDependencies string representation of dependencies to return
   * @return a set of {@code Dependency} that match given string representations
   */
  private Set<Dependency> toDependency(Set<Dependency> allDependencies, Set<String> ignoreDependencies) {
    return ignoreDependencies.stream()
        .map(dependency -> findDependency(allDependencies, dependency))
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private Dependency findDependency(Set<Dependency> allDependencies, String dependency) {
    return allDependencies.stream()
        .filter(dep -> dep.toString().toLowerCase().contains(dependency.toLowerCase()))
        .findFirst()
        .orElse(null);
  }

  private String getTime(long millis) {
    long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
    long seconds = (TimeUnit.MILLISECONDS.toSeconds(millis) % 60);
    return String.format("%smin %ss", minutes, seconds);
  }

  private void printString(final String string) {
    System.out.println(string); //NOSONAR avoid a warning of non-used logger
  }

  private Log getLog() {
    return dependencyManager.getLog();
  }
}
