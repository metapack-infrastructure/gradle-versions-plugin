/*
 * Copyright 2012-2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.gradle.versions.updates

import groovy.transform.TypeChecked
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.LenientConfiguration
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.dsl.ScriptHandler

import static groovy.transform.TypeCheckingMode.SKIP
import static org.gradle.api.specs.Specs.SATISFIES_ALL

/**
 * Resolves the configuration to determine the version status of its dependencies.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
class Resolver {
  final Project project
  final boolean useSelectionRules

  Resolver(Project project) {
    this.project = project

    useSelectionRules = new VersionComparator(project)
      .compare(project.gradle.gradleVersion, '2.2') >= 0

    logRepositories()
  }

  /** Returns the version status of the configuration's dependencies at the given revision. */
  public Set<DependencyStatus> resolve(Configuration configuration, String revision) {
    Map<Coordinate.Key, Coordinate> coordinates = getCurrentCoordinates(configuration)
    Configuration latestConfiguration = createLatestConfiguration(configuration, revision)

    LenientConfiguration lenient = latestConfiguration.resolvedConfiguration.lenientConfiguration
    Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
    return getStatus(coordinates, resolved, unresolved)
  }

  /** Returns the version status of the configuration's dependencies. */
  private Set<DependencyStatus> getStatus(Map<Coordinate.Key, Coordinate> coordinates,
      Set<ResolvedDependency> resolved,Set<UnresolvedDependency> unresolved) {
    Set<DependencyStatus> result = []
    for (ResolvedDependency dependency : resolved) {
      Coordinate resolvedCoordinate = Coordinate.from(dependency.module.id)
      Coordinate originalCoordinate = coordinates.get(resolvedCoordinate.key)
      Coordinate coord = originalCoordinate ?: resolvedCoordinate
      if ((originalCoordinate == null) && (resolvedCoordinate.groupId != 'null')) {
        project.logger.info("Skipping hidden dependency: ${resolvedCoordinate}")
        continue
      }
      result.add(new DependencyStatus(coord, resolvedCoordinate.version))
    }
    for (UnresolvedDependency dependency : unresolved) {
      Coordinate resolvedCoordinate = Coordinate.from(dependency.selector)
      Coordinate originalCoordinate = coordinates.get(resolvedCoordinate.key)
      Coordinate coord = originalCoordinate ?: resolvedCoordinate
      result.add(new DependencyStatus(coord, dependency))
    }
    return result
  }

  /** Returns a copy of the configuration where dependencies will be resolved up to the revision. */
  private Configuration createLatestConfiguration(Configuration configuration, String revision) {
    List<Dependency> latest = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collect { dependency ->
      createQueryDependency(dependency, revision)
    }

    Configuration copy = configuration.copyRecursive().setTransitive(false)
    copy.dependencies.clear()
    copy.dependencies.addAll(latest)

    if (useSelectionRules) {
      addRevisionFilter(copy, revision)
    }
    return copy
  }

  /** Returns a variant of the provided dependency used for querying the latest version. */
  @TypeChecked(SKIP)
  private Dependency createQueryDependency(Dependency dependency, String revision) {
    String versionQuery = useSelectionRules ? '+' : "latest.${revision}"
    String version = (dependency.version == null) ? 'none' : versionQuery
    return project.dependencies.create("${dependency.group}:${dependency.name}:${version}") {
      transitive = false
    }
  }

  /** Add a revision filter by rejecting candidates using a component selection rule. */
  @TypeChecked(SKIP)
  private void addRevisionFilter(Configuration configuration, String revision) {
    configuration.resolutionStrategy { ResolutionStrategy componentSelection ->
      componentSelection.componentSelection { rules ->
        rules.all { ComponentSelection selection, ComponentMetadata metadata ->
          boolean accepted =
            ((revision == 'release') && (metadata.status == 'release')) ||
            ((revision == 'milestone') && (metadata.status != 'integration')) ||
            (revision == 'integration') || (selection.candidate.version == 'none')
          if (!accepted) {
            selection.reject("Component status ${metadata.status} rejected by revision ${revision}")
          }
        }
      }
    }
  }

  /** Returns the coordinates for the current (declared) dependency versions. */
  private Map<Coordinate.Key, Coordinate> getCurrentCoordinates(Configuration configuration) {
    Map<Coordinate.Key, Coordinate> declared = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collectEntries {
      Coordinate coordinate = Coordinate.from(it)
      return [coordinate.key, coordinate]
    }
    if (declared.isEmpty()) {
      return Collections.emptyMap()
    }

    Map<Coordinate.Key, Coordinate> coordinates = [:]
    Configuration copy = configuration.copyRecursive().setTransitive(false)
    LenientConfiguration lenient = copy.resolvedConfiguration.lenientConfiguration

    Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    for (ResolvedDependency dependency : resolved) {
      Coordinate coordinate = Coordinate.from(dependency.module.id)
      coordinates.put(coordinate.key, coordinate)
    }

    Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
    for (UnresolvedDependency dependency : unresolved) {
      Coordinate coordinate = Coordinate.from(dependency.selector)
      coordinates.put(coordinate.key, declared.get(coordinate.key))
    }

    // Ignore undeclared (hidden) dependencies that appear when resolving a configuration
    coordinates.keySet().retainAll(declared.keySet())

    return coordinates
  }

  private void logRepositories() {
    boolean root = (project.rootProject == project)
    String label = "${root ? project.name : project.path} project${root ? ' (root)' : ''}"
    if (!project.buildscript.configurations*.dependencies.isEmpty()) {
      project.logger.info("Resolving ${label} buildscript with repositories:")
      for (ArtifactRepository repository : project.buildscript.repositories) {
        logRepository(repository)
      }
    }
    project.logger.info("Resolving ${label} configurations with repositories:")
    for (ArtifactRepository repository : project.repositories) {
      logRepository(repository)
    }
  }

  @TypeChecked(SKIP)
  private void logRepository(ArtifactRepository repository) {
    if (repository instanceof FlatDirectoryArtifactRepository) {
      project.logger.info(" - ${repository.name}: ${repository.dirs}")
    } else if (repository instanceof MavenArtifactRepository ||
        repository instanceof IvyArtifactRepository) {
      project.logger.info(" - ${repository.name}: ${repository.url}");
    } else {
      project.logger.info(" - ${repository.name}: ${repository.getClass().simpleName}")
    }
  }
}