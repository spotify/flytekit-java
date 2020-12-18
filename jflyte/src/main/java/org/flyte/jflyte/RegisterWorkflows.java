/*
 * Copyright 2020 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.flyte.jflyte;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.flyte.api.v1.Container;
import org.flyte.api.v1.KeyValuePair;
import org.flyte.api.v1.LaunchPlan;
import org.flyte.api.v1.LaunchPlanIdentifier;
import org.flyte.api.v1.LaunchPlanRegistrar;
import org.flyte.api.v1.RunnableTask;
import org.flyte.api.v1.RunnableTaskRegistrar;
import org.flyte.api.v1.TaskIdentifier;
import org.flyte.api.v1.TaskTemplate;
import org.flyte.api.v1.WorkflowIdentifier;
import org.flyte.api.v1.WorkflowTemplate;
import org.flyte.api.v1.WorkflowTemplateRegistrar;
import org.flyte.jflyte.api.FileSystem;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Registers all workflows on classpath. */
@Command(name = "workflows")
public class RegisterWorkflows implements Callable<Integer> {

  @SuppressWarnings("UnusedVariable")
  @Option(
      names = {"-p", "--project"},
      description = "Flyte project to use. You can have more than one project per repo",
      required = true)
  private String project;

  @SuppressWarnings("UnusedVariable")
  @Option(
      names = {"-d", "--domain"},
      description = "This is usually development, staging, or production",
      required = true)
  private String domain;

  @SuppressWarnings("UnusedVariable")
  @Option(
      names = {"-v", "--version"},
      description = "This is version to apply globally for this context",
      required = true)
  private String version;

  @Option(
      names = {"-cp", "--classpath"},
      description = "Directory with packaged jars",
      required = false)
  private String packageDir;

  @Override
  public Integer call() {
    Config config = Config.load();

    try (FlyteAdminClient adminClient =
        FlyteAdminClient.create(config.platformUrl(), config.platformInsecure())) {

      String indexFileLocation;
      ClassLoader packageClassLoader;

      if (packageDir != null) {
        ArtifactStager stager = getArtifactStager(config);
        List<Artifact> artifacts = stagePackageFiles(stager, packageDir);
        Artifact indexFile = stageIndexFile(stager, artifacts);

        packageClassLoader = ClassLoaders.forDirectory(new File(packageDir));
        indexFileLocation = indexFile.location();
      } else {
        if (config.mainModule() == null) {
          throw new IllegalArgumentException(
              "FLYTE_INTERNAL_MAIN_MODULE env. variable or --classpath must be specified. ");
        }

        packageClassLoader =
            ClassLoaders.forDirectory(new File(config.moduleDir(), config.mainModule()));
        indexFileLocation = null;
      }

      registerAll(
          /* image= */ config.image(),
          /* indexFileLocation= */ indexFileLocation,
          /* packageClassLoader= */ packageClassLoader,
          /* adminClient= */ adminClient);
    }

    return 0;
  }

  private ArtifactStager getArtifactStager(Config config) {
    try {
      String stagingLocation = config.stagingLocation();

      if (stagingLocation == null) {
        throw new IllegalArgumentException(
            "Environment variable 'FLYTE_STAGING_LOCATION' isn't set");
      }

      URI stagingUri = new URI(stagingLocation);
      Collection<ClassLoader> modules = ClassLoaders.forModuleDir(config.moduleDir()).values();
      Map<String, FileSystem> fileSystems = FileSystemLoader.loadFileSystems(modules);
      FileSystem stagingFileSystem = FileSystemLoader.getFileSystem(fileSystems, stagingUri);

      return new ArtifactStager(stagingLocation, stagingFileSystem);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Failed to parse stagingLocation", e);
    }
  }

  private TaskTemplate createTaskTemplate(
      RunnableTask task, @Nullable String indexFileLocation, String image, List<KeyValuePair> env) {

    ImmutableList.Builder<String> argsBuilder =
        ImmutableList.<String>builder()
            .add(
                "jflyte",
                "execute",
                "--task",
                task.getName(),
                "--inputs",
                "{{.input}}",
                "--outputPrefix",
                "{{.outputPrefix}}");

    if (indexFileLocation != null) {
      argsBuilder.add("--indexFileLocation", indexFileLocation);
    }

    Container container =
        Container.builder()
            .command(ImmutableList.of())
            .args(argsBuilder.build())
            .image(image)
            .env(env)
            .build();

    return TaskTemplate.builder()
        .container(container)
        .interface_(task.getInterface())
        .retries(task.getRetries())
        .build();
  }

  private void registerAll(
      String image,
      @Nullable String indexFileLocation,
      ClassLoader packageClassLoader,
      FlyteAdminClient adminClient) {
    Map<String, String> env =
        ImmutableMap.of(
            "JFLYTE_DOMAIN", domain,
            "JFLYTE_PROJECT", project,
            "JFLYTE_VERSION", version);

    List<KeyValuePair> envList =
        env.entrySet().stream()
            .map(entry -> KeyValuePair.of(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

    // before we run anything, switch class loader, because we will be touching user classes;
    // setting it in thread context will give us access to the right class loader

    Map<TaskIdentifier, RunnableTask> tasks =
        ClassLoaders.withClassLoader(
            packageClassLoader, () -> Registrars.loadAll(RunnableTaskRegistrar.class, env));

    Map<WorkflowIdentifier, WorkflowTemplate> workflows =
        ClassLoaders.withClassLoader(
            packageClassLoader, () -> Registrars.loadAll(WorkflowTemplateRegistrar.class, env));

    Map<LaunchPlanIdentifier, LaunchPlan> launchPlans =
        ClassLoaders.withClassLoader(
            packageClassLoader, () -> Registrars.loadAll(LaunchPlanRegistrar.class, env));

    IdentifierRewrite identifierRewrite =
        IdentifierRewrite.builder()
            .adminClient(adminClient)
            .domain(domain)
            .project(project)
            .version(version)
            .build();

    for (Map.Entry<TaskIdentifier, RunnableTask> entry : tasks.entrySet()) {
      TaskIdentifier taskId = entry.getKey();
      RunnableTask task = entry.getValue();

      TaskTemplate taskTemplate =
          createTaskTemplate(
              task,
              /* indexFileLocation= */ indexFileLocation,
              /* image= */ image,
              /* env= */ envList);

      adminClient.createTask(taskId, taskTemplate);
    }

    for (Map.Entry<WorkflowIdentifier, WorkflowTemplate> entry : workflows.entrySet()) {
      WorkflowIdentifier workflowId = entry.getKey();
      WorkflowTemplate workflowTemplate = identifierRewrite.apply(entry.getValue());

      adminClient.createWorkflow(workflowId, workflowTemplate);
    }

    for (Map.Entry<LaunchPlanIdentifier, LaunchPlan> entry : launchPlans.entrySet()) {
      LaunchPlanIdentifier launchPlanId = entry.getKey();
      LaunchPlan launchPlan = identifierRewrite.apply(entry.getValue());

      adminClient.createLaunchPlan(launchPlanId, launchPlan);
    }
  }

  private static List<Artifact> stagePackageFiles(ArtifactStager stager, String packageDir) {
    try (Stream<Path> fileStream = Files.list(Paths.get(packageDir))) {
      List<String> files =
          fileStream.map(x -> x.toFile().getAbsolutePath()).collect(Collectors.toList());

      return stager.stageFiles(files);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Artifact stageIndexFile(ArtifactStager stager, List<Artifact> packageArtifacts) {
    // TODO use json, or something. json is nice because it's human-readable
    // TODO add crc32c checksums along with location
    String content =
        packageArtifacts.stream()
            .sorted(Comparator.comparing(Artifact::location))
            .map(Artifact::location)
            .collect(Collectors.joining("\n"));

    ByteSource contentBytes = ByteSource.wrap(content.getBytes(Charsets.UTF_8));

    Artifact indexArtifact = stager.getArtifact("classpath", contentBytes);

    stager.stageArtifact(indexArtifact, contentBytes);

    return indexArtifact;
  }
}
