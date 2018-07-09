/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.util;

/**
 * Replacement implementation of the NativeCodeLoader to not attempt to load
 * native code, which is irrelevant for this S3 connector.
 */
@SuppressWarnings("unused")
public class NativeCodeLoader {

  public static boolean isNativeCodeLoaded() {
    return false;
  }

  public static boolean buildSupportsSnappy() {
    return false;
  }
  
  public static boolean buildSupportsOpenssl() {
    return false;
  }

  public static boolean buildSupportsIsal() {
    return false;
  }

  public static boolean buildSupportsZstd() {
    return false;
  }

  public static String getLibraryName() {
    return null;
  }

  private NativeCodeLoader() {}
}
