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

package com.facebook.buck.remoteexecution.thrift.cas;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.CasBlobDownloadEvent;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.thrift.ClientPool;
import com.facebook.buck.remoteexecution.thrift.PooledClient;
import com.facebook.buck.remoteexecution.thrift.ThriftProtocol;
import com.facebook.buck.util.Scope;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorage.Iface;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.facebook.thrift.TException;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

/** A Thrift-based implementation of fetching outputs from the CAS. */
// TODO(shivanker): Make this implementation actually async.
public class ThriftBlobFetcher implements AsyncBlobFetcher {

  private final ClientPool<ContentAddressableStorage.Iface> clientPool;
  private final BuckEventBus eventBus;

  public ThriftBlobFetcher(
      ClientPool<ContentAddressableStorage.Iface> clientPool, BuckEventBus eventBus) {
    this.clientPool = clientPool;
    this.eventBus = eventBus;
  }

  @Override
  public ListenableFuture<ByteBuffer> fetch(Protocol.Digest digest) {
    ReadBlobRequest request = new ReadBlobRequest(ThriftProtocol.get(digest));
    try (Scope ignore = CasBlobDownloadEvent.sendEvent(eventBus, 1, digest.getSize());
        PooledClient<Iface> pooledClient = clientPool.getPooledClient()) {
      ReadBlobResponse response;
      response = pooledClient.getRawClient().readBlob(request);
      return Futures.immediateFuture(ByteBuffer.wrap(response.getData()));
    } catch (TException | ContentAddressableStorageException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  @Override
  public ListenableFuture<Void> fetchToStream(Digest digest, OutputStream outputStream) {
    return Futures.transformAsync(
        fetch(digest),
        data -> {
          try {
            Channels.newChannel(outputStream).write(data);
            return Futures.immediateFuture(null);
          } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
          }
        });
  }
}
