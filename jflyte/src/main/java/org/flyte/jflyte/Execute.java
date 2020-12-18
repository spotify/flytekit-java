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

import static org.flyte.jflyte.ClassLoaders.withClassLoader;

import flyteidl.core.Errors;
import flyteidl.core.Literals;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.flyte.api.v1.ContainerError;
import org.flyte.api.v1.Literal;
import org.flyte.api.v1.RunnableTask;
import org.flyte.api.v1.RunnableTaskRegistrar;
import org.flyte.api.v1.TaskIdentifier;
import org.flyte.jflyte.api.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Handler for "execute" command. */
@Command(name = "execute")
public class Execute implements Callable<Integer> {

  private static final Logger LOG = LoggerFactory.getLogger(Execute.class);
  private static final String OUTPUTS_PB = "outputs.pb";
  private static final String ERROR_PB = "error.pb";

  @Option(
      names = {"--task"},
      required = true)
  private String task;

  @Option(
      names = {"--inputs"},
      required = true)
  private String inputs;

  @SuppressWarnings("UnusedVariable")
  @Option(
      names = {"--outputPrefix"},
      required = true)
  private String outputPrefix;

  @Option(
      names = {"--indexFileLocation"},
      required = false)
  private String indexFileLocation;

  @Override
  public Integer call() {
    execute();
    return 0;
  }

  private void execute() {
    Config config = Config.load();
    Map<String, ClassLoader> modules = ClassLoaders.forModuleDir(config.moduleDir());
    Map<String, FileSystem> fileSystems = FileSystemLoader.loadFileSystems(modules.values());
    ClassLoader packageClassLoader = loadPackage(config, fileSystems, modules);

    FileSystem inputFs = FileSystemLoader.getFileSystem(fileSystems, inputs);
    FileSystem outputFs = FileSystemLoader.getFileSystem(fileSystems, outputPrefix);

    try {
      // before we run anything, switch class loader, otherwise,
      // ServiceLoaders and other things wouldn't work, for instance,
      // FileSystemRegister in Apache Beam

      Map<String, Literal> outputs =
          withClassLoader(
              packageClassLoader,
              () -> {
                Map<String, Literal> input = getInput(inputFs, inputs);
                RunnableTask runnableTask = getTask(task);

                return runnableTask.run(input);
              });

      writeOutputs(outputFs, outputPrefix, outputs);
    } catch (ContainerError e) {
      LOG.error("failed to run task", e);

      writeError(outputFs, outputPrefix, ProtoUtil.serializeContainerError(e));
    } catch (Throwable e) {
      LOG.error("failed to run task", e);

      writeError(outputFs, outputPrefix, ProtoUtil.serializeThrowable(e));
    }
  }

  // We support two modes: using staged class path (--indexFileLocation), or using standalone docker
  // image
  // and specifying FLYTE_INTERNAL_MAIN_MODULE.
  private ClassLoader loadPackage(
      Config config, Map<String, FileSystem> fileSystems, Map<String, ClassLoader> modules) {
    if (indexFileLocation != null) {
      List<String> stagedFiles = readStagedFiles(fileSystems, indexFileLocation);

      return loadStagedPackage(fileSystems, stagedFiles);
    } else {
      if (config.mainModule() == null) {
        throw new IllegalArgumentException(
            "FLYTE_INTERNAL_MAIN_MODULE env. variable or --indexFileLocation "
                + "must be set specified. If you are seeing this error, likely something "
                + "is wrong with docker image packaging.");
      }

      ClassLoader mainModule = modules.get(config.mainModule());

      if (mainModule == null) {
        String absolutePath = new File(config.moduleDir(), config.mainModule()).getAbsolutePath();
        throw new IllegalArgumentException(
            String.format(
                "Can't find any jars in [%s] module. "
                    + "Please double-check module directory [%s] in [%s] Docker image",
                config.mainModule(), absolutePath, config.image()));
      }

      return mainModule;
    }
  }

  private static void writeOutputs(
      FileSystem fs, String outputPrefix, Map<String, Literal> outputs) {
    String outputUri = normalizeUri(outputPrefix, OUTPUTS_PB);

    writeTo(
        fs,
        outputUri,
        outputStream -> {
          Literals.LiteralMap proto = ProtoUtil.serialize(outputs);
          proto.writeTo(outputStream);
        });
  }

  private static void writeError(
      FileSystem fs, String outputPrefix, Errors.ContainerError containerError) {
    String outputUri = normalizeUri(outputPrefix, ERROR_PB);

    writeTo(
        fs,
        outputUri,
        outputStream -> {
          Errors.ErrorDocument errorDocument =
              Errors.ErrorDocument.newBuilder().setError(containerError).build();

          errorDocument.writeTo(outputStream);
        });
  }

  private static void writeTo(FileSystem fs, String uri, Writer writer) {
    try (WritableByteChannel channel = fs.writer(uri);
        OutputStream os = Channels.newOutputStream(channel)) {
      writer.write(os);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String normalizeUri(String prefix, String fileName) {
    String uri;
    if (prefix.endsWith("/")) {
      uri = prefix + fileName;
    } else {
      uri = prefix + "/" + fileName;
    }
    return uri;
  }

  private static Map<String, Literal> getInput(FileSystem fs, String uri) {
    try (ReadableByteChannel channel = fs.reader(uri)) {
      Literals.LiteralMap proto = Literals.LiteralMap.parseFrom(Channels.newInputStream(channel));

      return ProtoUtil.deserialize(proto);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static RunnableTask getTask(String name) {
    // be careful not to pass extra
    Map<String, String> env =
        System.getenv().entrySet().stream()
            .filter(x -> x.getKey().startsWith("JFLYTE_"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Map<TaskIdentifier, RunnableTask> tasks = Registrars.loadAll(RunnableTaskRegistrar.class, env);

    for (Map.Entry<TaskIdentifier, RunnableTask> entry : tasks.entrySet()) {
      if (entry.getKey().name().equals(name)) {
        return entry.getValue();
      }
    }

    throw new IllegalArgumentException("Task not found: " + name);
  }

  private static List<String> readStagedFiles(
      Map<String, FileSystem> fileSystems, String indexFileLocation) {
    FileSystem fileSystem = FileSystemLoader.getFileSystem(fileSystems, indexFileLocation);
    List<String> files = new ArrayList<>();

    try (ReadableByteChannel reader = fileSystem.reader(indexFileLocation)) {
      Scanner scanner = new Scanner(Channels.newInputStream(reader), "UTF-8");

      while (scanner.hasNext()) {
        String next = scanner.next();

        LOG.info("Read staged file {}", next);

        if (!next.isEmpty()) {
          files.add(next);
        }
      }

      return files;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ClassLoader loadStagedPackage(
      Map<String, FileSystem> fileSystems, List<String> stagedFiles) {
    try {
      Path tmp = Files.createTempDirectory("tasks");

      // TODO do in parallel

      for (String stagedFile : stagedFiles) {
        FileSystem fileSystem = FileSystemLoader.getFileSystem(fileSystems, stagedFile);

        try (ReadableByteChannel reader = fileSystem.reader(stagedFile)) {
          // FIXME beam doesn't like = in jar names
          // we should preserve original jar name, for now, just remove "="
          String name = stagedFile.substring(stagedFile.lastIndexOf("/") + 1).replace("=", "");
          Path path = tmp.resolve(name);

          if (path.toFile().exists()) {
            // file already exists, but we have checksums, so we should be ok
            LOG.warn("Duplicate entry in --stagedFiles: [{}]", stagedFile);
            continue;
          }

          LOG.info("Copied {} to {}", stagedFile, path);

          Files.copy(Channels.newInputStream(reader), path);
        }
      }

      return ClassLoaders.forDirectory(tmp.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private interface Writer {
    void write(OutputStream os) throws IOException;
  }
}
