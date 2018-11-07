/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.parser.cache.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.cache.ParserCacheException;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.config.Config;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** This class implements the caching behavior for parsing build specs. */
public class ParserCache {
  private static final Logger LOG = Logger.get(ParserCache.class);

  private final ParserCacheStorage parserCacheStorage;
  private final ProjectFilesystem filesystem;
  private final Config config;

  private ParserCache(
      Config config, ProjectFilesystem filesystem, ParserCacheStorage parserCacheStorage) {
    this.filesystem = filesystem;
    this.config = config;
    this.parserCacheStorage = parserCacheStorage;
  }

  /**
   * Creates a {@link ParserCache} object.
   *
   * @param filesystem the {link ProjectFilesystem} to use for locating the local cache.
   * @return a new instance of a caching parser.
   */
  public static ParserCache of(
      BuckConfig buckConfig,
      ProjectFilesystem filesystem,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier) {
    return new ParserCache(
        buckConfig.getConfig(),
        filesystem,
        ParserCacheStorageFactory.createParserCacheStorage(
            buckConfig, filesystem, manifestServiceSupplier));
  }

  @VisibleForTesting
  ParserCacheStorage getParserCacheStorage() {
    return parserCacheStorage;
  }

  /**
   * This method looks for the cached result for this build spec and returns a constructed {@link
   * BuildFileManifest}.
   *
   * @return a constructed result object based on the cache found data or {@code Optional.empty()}
   *     if no cache info is found.
   */
  private Optional<BuildFileManifest> getManifestFromLocalCache(
      Path buildFile, ImmutableList<String> includeBuildFiles) throws IOException {
    final HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildFile, config);
    final HashCode strongFingerprint =
        Fingerprinter.getStrongFingerprint(filesystem, includeBuildFiles);
    try {
      return parserCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
    } catch (ParserCacheException t) {
      LOG.error(t, "Exception getting BuildFileManifest from cache.");
    }

    return Optional.empty();
  }

  private byte[] serializeBuildFileManifestToBytes(
      BuildFileManifest buildFileManifest, Path buildFile) throws ParserCacheException {
    byte[] serializedBuildFileManifest;
    try {
      serializedBuildFileManifest = BuildFileManifestSerializer.serialize(buildFileManifest);
    } catch (IOException e) {
      throw new ParserCacheException(
          e, "Failed to serialize BuildFileManifgest - path %s.", buildFile);
    }
    return serializedBuildFileManifest;
  }

  /**
   * Store a parsed entry in the cache.
   *
   * @param buildFileManifest the {@code BuildFileManifest} to store.
   */
  public void storeBuildFileManifest(Path buildFile, BuildFileManifest buildFileManifest) {
    final HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildFile, config);
    try {
      final HashCode strongFingerprint =
          Fingerprinter.getStrongFingerprint(filesystem, buildFileManifest.getIncludes());
      byte[] serializedManifest = serializeBuildFileManifestToBytes(buildFileManifest, buildFile);
      parserCacheStorage.storeBuildFileManifest(
          weakFingerprint, strongFingerprint, serializedManifest);
    } catch (ParserCacheException e) {
      LOG.error(e, "Failure while storing parsed BuildFileAccessManifest.");
    } catch (IOException e) {
      LOG.error(
          e,
          "Failure while calculating strong fingerprint when storing parsed BuildFileAccessManifest.");
    }
  }

  /**
   * Get build file manifest for the appropriate build file from cache, ignoring errors
   *
   * @param buildFile BUCK file to be parsed
   * @param parser Parser to retrieve meta information, like includes and globs, from build file
   * @return {@link BuildFileManifest} if found in cache, {@link Optional#empty() if not found or if
   *     there was an error accessing the cache}
   */
  public Optional<BuildFileManifest> getBuildFileManifest(
      Path buildFile, ProjectBuildFileParser parser) throws InterruptedException {

    // Get from local cache.
    try {
      ImmutableList<String> includeFilesForBuildFile = parser.getIncludedFiles(buildFile);
      Optional<BuildFileManifest> cachedManifest =
          getManifestFromLocalCache(buildFile, includeFilesForBuildFile);
      if (cachedManifest.isPresent()
          && parser.globResultsMatchCurrentState(
              buildFile, cachedManifest.get().getGlobManifest())) {
        // There is a match only if the glob state on disk is the same as the recorded globs and
        // results.
        return cachedManifest;
      }
    } catch (IOException e) {
      LOG.error(e, "Error getting BuildFileManifest from cache, will reparse BUCK file");
    }

    return Optional.empty();
  }
}
