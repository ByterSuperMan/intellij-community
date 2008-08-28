/*
 * Copyright 2000-2007 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.Charset;

/**
 * Represents a path to a (possibly non-existing) file on disk or in a VCS repository.
 */
public interface FilePath {
  VirtualFile getVirtualFile();
  VirtualFile getVirtualFileParent();

  @NotNull File getIOFile();

  String getName();

  String getPresentableUrl();

  @Nullable
  Document getDocument();

  Charset getCharset();

  FileType getFileType();

  void refresh();

  String getPath();

  boolean isDirectory();

  /**
   * Check if the provided file is an ancestor of the current file.
   * @param parent a possible parent
   * @param strict if true, the method also returns true if files are equal
   * @return true if {@code this} file is ancestor of the {@code parent}.
   */
  boolean isUnder(FilePath parent, boolean strict);

  @Nullable
  FilePath getParentPath();

  boolean isNonLocal();
}
