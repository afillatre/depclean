package se.kth.depclean.core.model;

import static com.google.common.collect.ImmutableSet.copyOf;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import se.kth.depclean.core.analysis.graph.DependencyGraph;

/**
 * Contains all information about the project's context. It doesn't have any reference to a given framework (Maven, Gradle, etc.).
 */
@Slf4j
@ToString
@EqualsAndHashCode
public final class ProjectContext {

  private final Multimap<Dependency, ClassName> classesPerDependency = ArrayListMultimap.create();
  private final Multimap<ClassName, Dependency> dependenciesPerClass = ArrayListMultimap.create();

  @Getter
  private final Path outputFolder;
  @Getter
  private final Path testOutputFolder;
  @Getter
  private final Path sourceFolder;
  @Getter
  private final Path testFolder;
  @Getter
  private final Path dependenciesFolder;


  @Getter
  private final Set<Scope> ignoredScopes;
  @Getter
  private final Set<Dependency> ignoredDependencies;
  @Getter
  private final Set<ClassName> extraClasses;
  @Getter
  private final DependencyGraph dependencyGraph;

  /**
   * Creates a new project context.
   *
   * @param dependencyGraph     the dependencyGraph
   * @param outputFolder        where the project's classes are compiled
   * @param testOutputFolder    where the project's test classes are compiled
   * @param sourceFolder        where the project's source code are located
   * @param tesSourceFolder     where the project's test sources are located
   * @param dependenciesFolder  where the dependency classes are located
   * @param ignoredScopes       the scopes to ignore
   * @param ignoredDependencies the dependencies to ignore (i.e. considered as 'used')
   * @param extraClasses        some classes we want to tell the analyser to consider used
   */
  public ProjectContext(DependencyGraph dependencyGraph,
      Path outputFolder, Path testOutputFolder,
      Path sourceFolder, Path tesSourceFolder, Path dependenciesFolder, Set<Scope> ignoredScopes,
      Set<Dependency> ignoredDependencies,
      Set<ClassName> extraClasses) {
    this.dependencyGraph = dependencyGraph;
    this.outputFolder = outputFolder;
    this.testOutputFolder = testOutputFolder;
    this.sourceFolder = sourceFolder;
    this.testFolder = tesSourceFolder;
    this.dependenciesFolder = dependenciesFolder;
    this.ignoredScopes = ignoredScopes;
    this.ignoredDependencies = ignoredDependencies;
    this.extraClasses = extraClasses;

    ignoredScopes.forEach(scope -> log.info("Ignoring scope {}", scope));

    populateDependenciesAndClassesMap(dependencyGraph.directDependencies());
    populateDependenciesAndClassesMap(dependencyGraph.inheritedDependencies());
    populateDependenciesAndClassesMap(dependencyGraph.transitiveDependencies());

    Multimaps.invertFrom(classesPerDependency, dependenciesPerClass);
  }

  public Set<ClassName> getClassesForDependency(Dependency dependency) {
    return copyOf(classesPerDependency.get(dependency));
  }

  public Set<Dependency> getDependenciesForClass(ClassName className) {
    return copyOf(dependenciesPerClass.get(className));
  }

  public boolean hasNoDependencyOnClass(ClassName className) {
    return Iterables.isEmpty(getDependenciesForClass(className));
  }

  /**
   * Get all known dependencies.
   *
   * @return all known dependencies
   */
  public Set<Dependency> getAllDependencies() {
    final Set<Dependency> dependencies = new HashSet<>(dependencyGraph.allDependencies());
    dependencies.add(dependencyGraph.projectCoordinates());
    return copyOf(dependencies);
  }

  public boolean ignoreTests() {
    return ignoredScopes.contains(new Scope("test"));
  }

  private void populateDependenciesAndClassesMap(Set<Dependency> dependencies) {
    dependencies.stream()
        .filter(this::filterScopesIfNeeded)
        .forEach(dc -> classesPerDependency.putAll(dc, dc.getRelatedClasses()));
  }

  private boolean filterScopesIfNeeded(Dependency dc) {
    final String declaredScope = dc.getScope();
    return ignoredScopes.stream()
        .map(Scope::getValue)
        .noneMatch(declaredScope::equalsIgnoreCase);
  }
}
