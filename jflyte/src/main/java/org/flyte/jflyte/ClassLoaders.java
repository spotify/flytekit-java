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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility to work with class loaders. */
class ClassLoaders {

  private static final Logger LOG = LoggerFactory.getLogger(ClassLoaders.class);

  private ClassLoaders() {
    throw new UnsupportedOperationException();
  }

  static Map<String, ClassLoader> forModuleDir(String dir) {
    return listDirectory(new File(dir)).stream()
        .filter(File::isDirectory)
        .map(subDir -> Maps.immutableEntry(subDir.getName(), forDirectory(subDir)))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  static ClassLoader forDirectory(File dir) {
    LOG.debug("Loading jars from [{}]", dir.getAbsolutePath());

    return AccessController.doPrivileged(
        (PrivilegedAction<ClassLoader>) () -> new ChildFirstClassLoader(getClassLoaderUrls(dir)));
  }

  static URL[] getClassLoaderUrls(File dir) {
    Preconditions.checkNotNull(dir, "dir is null");

    return listDirectory(dir).stream()
        .map(
            file -> {
              try {
                URL url = file.toURI().toURL();
                LOG.debug("Discovered [{}]", url);

                return url;
              } catch (MalformedURLException e) {
                throw new RuntimeException(e);
              }
            })
        .toArray(URL[]::new);
  }

  static List<File> listDirectory(File file) {
    if (!file.exists()) {
      throw new RuntimeException(
          String.format("Directory doesn't exist [%s]", file.getAbsolutePath()));
    }

    File[] files = file.listFiles();

    if (!file.isDirectory() || files == null) {
      throw new RuntimeException(String.format("Isn't directory [%s]", file.getAbsolutePath()));
    }

    return ImmutableList.copyOf(files);
  }

  static <V> V withClassLoader(ClassLoader classLoader, Callable<V> callable) {
    ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(classLoader);

    // before we run anything, switch class loader, because we will be touching user classes;
    // setting it in thread context will give us access to the right class loader

    try {
      return callable.call();
    } catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }

      throw new RuntimeException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(originalContextClassLoader);
    }
  }
}
