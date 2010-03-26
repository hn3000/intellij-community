/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 11-Nov-2008
 */
package org.jetbrains.idea.eclipse.conversion;

import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.eclipse.EclipseXml;
import org.jetbrains.idea.eclipse.IdeaXml;
import org.jetbrains.idea.eclipse.config.EclipseModuleManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EclipseClasspathWriter {
  private ModuleRootModel myModel;
  private Map<String, Element> myOldEntries = new HashMap<String, Element>();

  public EclipseClasspathWriter(final ModuleRootModel model) {
    myModel = model;
  }

  public void writeClasspath(Element classpathElement, @Nullable Element oldRoot) throws ConversionException {
    if (oldRoot != null) {
      for (Object o : oldRoot.getChildren(EclipseXml.CLASSPATHENTRY_TAG)) {
        final Element oldChild = (Element)o;
        final String oldKind = oldChild.getAttributeValue(EclipseXml.KIND_ATTR);
        final String oldPath = oldChild.getAttributeValue(EclipseXml.PATH_ATTR);
        myOldEntries.put(oldKind + getJREKey(oldPath), oldChild);
      }
    }

    for (OrderEntry orderEntry : myModel.getOrderEntries()) {
      createClasspathEntry(orderEntry, classpathElement);
    }

    @NonNls String outputPath = "bin";
    final VirtualFile contentRoot = EPathUtil.getContentRoot(myModel);
    final VirtualFile output = myModel.getModuleExtension(CompilerModuleExtension.class).getCompilerOutputPath();
    if (contentRoot != null && output != null && VfsUtil.isAncestor(contentRoot, output, false)) {
      outputPath = EPathUtil.collapse2EclipsePath(output.getUrl(), myModel);
    }
    else if (output == null) {
      final String url = myModel.getModuleExtension(CompilerModuleExtension.class).getCompilerOutputUrl();
      if (url != null) {
        outputPath = EPathUtil.collapse2EclipsePath(url, myModel);
      }
    }
    final Element orderEntry = addOrderEntry(EclipseXml.OUTPUT_KIND, outputPath, classpathElement);
    setAttributeIfAbsent(orderEntry, EclipseXml.PATH_ATTR, EclipseXml.BIN_DIR);
  }

  private void createClasspathEntry(OrderEntry entry, Element classpathRoot) throws ConversionException {
    if (entry instanceof ModuleSourceOrderEntry) {
      final ModuleRootModel rootModel = ((ModuleSourceOrderEntry)entry).getRootModel();
      final ContentEntry[] entries = rootModel.getContentEntries();
      for (final ContentEntry contentEntry : entries) {
        final VirtualFile contentRoot = contentEntry.getFile();
        for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
          String relativePath = EPathUtil.collapse2EclipsePath(sourceFolder.getUrl(), myModel);
          if (contentRoot != EPathUtil.getContentRoot(rootModel)) {
            final String linkedPath = EclipseModuleManager.getInstance(entry.getOwnerModule()).getEclipseLinkedSrcVariablePath(sourceFolder.getUrl());
            if (linkedPath != null) {
              relativePath = linkedPath;
            }
          }
          addOrderEntry(EclipseXml.SRC_KIND, relativePath, classpathRoot);
        }
      }
    }
    else if (entry instanceof ModuleOrderEntry) {
      Element orderEntry = addOrderEntry(EclipseXml.SRC_KIND, "/" + ((ModuleOrderEntry)entry).getModuleName(), classpathRoot);
      setAttributeIfAbsent(orderEntry, EclipseXml.COMBINEACCESSRULES_ATTR, EclipseXml.FALSE_VALUE);
      setExported(orderEntry, ((ExportableOrderEntry)entry));
    }
    else if (entry instanceof LibraryOrderEntry) {
      final LibraryOrderEntry libraryOrderEntry = (LibraryOrderEntry)entry;
      final String libraryName = libraryOrderEntry.getLibraryName();
      if (libraryOrderEntry.isModuleLevel()) {
        final String[] files = libraryOrderEntry.getUrls(OrderRootType.CLASSES);
        if (files.length > 0) {
          if (libraryName != null &&
              libraryName.contains(IdeaXml.JUNIT) &&
              Comparing.strEqual(files[0], EclipseClasspathReader.getJunitClsUrl(libraryName.contains("4")))) {
            final Element orderEntry =
              addOrderEntry(EclipseXml.CON_KIND, EclipseXml.JUNIT_CONTAINER + "/" + libraryName.substring(IdeaXml.JUNIT.length()),
                            classpathRoot);
            setExported(orderEntry, libraryOrderEntry);
          }
          else {
            final String eclipseVariablePath = EclipseModuleManager.getInstance(libraryOrderEntry.getOwnerModule()).getEclipseVariablePath(files[0]);
            final Element orderEntry;
            if (eclipseVariablePath != null) {
              orderEntry = addOrderEntry(EclipseXml.VAR_KIND, eclipseVariablePath, classpathRoot);
            }
            else {
              orderEntry = addOrderEntry(EclipseXml.LIB_KIND, EPathUtil.collapse2EclipsePath(files[0], myModel), classpathRoot);
            }

            final String srcRelativePath;
            final String eclipseSrcVariablePath;

            final String[] srcFiles = libraryOrderEntry.getUrls(OrderRootType.SOURCES);
            if (srcFiles.length == 0) {
              srcRelativePath = null;
              eclipseSrcVariablePath = null;
            }
            else {
              final String lastSourceRoot = srcFiles[srcFiles.length - 1];
              srcRelativePath = EPathUtil.collapse2EclipsePath(lastSourceRoot, myModel);
              eclipseSrcVariablePath = EclipseModuleManager.getInstance(libraryOrderEntry.getOwnerModule()).getEclipseSrcVariablePath(lastSourceRoot);
            }
            setOrRemoveAttribute(orderEntry, EclipseXml.SOURCEPATH_ATTR, eclipseSrcVariablePath != null ? eclipseSrcVariablePath : srcRelativePath);

            setupLibraryAttributes(orderEntry, libraryOrderEntry);
            setExported(orderEntry, libraryOrderEntry);
          }
        }
      }
      else {
        final Element orderEntry;
        if (EclipseModuleManager.getInstance(libraryOrderEntry.getOwnerModule()).getUnknownCons().contains(libraryName)) {
          orderEntry = addOrderEntry(EclipseXml.CON_KIND, libraryName, classpathRoot);
        } else if (Comparing.strEqual(libraryName, IdeaXml.ECLIPSE_LIBRARY)) {
          orderEntry = addOrderEntry(EclipseXml.CON_KIND, EclipseXml.ECLIPSE_PLATFORM, classpathRoot);
        }
        else {
          orderEntry = addOrderEntry(EclipseXml.CON_KIND, EclipseXml.USER_LIBRARY + "/" + libraryName, classpathRoot);
        }
        setExported(orderEntry, libraryOrderEntry);
      }
    }
    else if (entry instanceof JdkOrderEntry) {
      if (entry instanceof InheritedJdkOrderEntry) {
        if (!EclipseModuleManager.getInstance(entry.getOwnerModule()).isForceConfigureJDK()) {
          addOrderEntry(EclipseXml.CON_KIND, EclipseXml.JRE_CONTAINER, classpathRoot);
        }
      }
      else {
        final Sdk jdk = ((JdkOrderEntry)entry).getJdk();
        String jdkLink;
        if (jdk == null) {
          jdkLink = EclipseXml.JRE_CONTAINER;
        }
        else {
          jdkLink = EclipseXml.JRE_CONTAINER;
          if (jdk.getSdkType() instanceof JavaSdkType) {
            jdkLink += EclipseXml.JAVA_SDK_TYPE;
          }
          jdkLink += "/" + jdk.getName();
        }
        addOrderEntry(EclipseXml.CON_KIND, jdkLink, classpathRoot);
      }
    }
    else {
      throw new ConversionException("Unknown EclipseProjectModel.ClasspathEntry: " + entry.getClass());
    }
  }

  private void setupLibraryAttributes(Element orderEntry, LibraryOrderEntry libraryOrderEntry) {
    final List<String> eclipseUrls = new ArrayList<String>();
    final String[] docUrls = libraryOrderEntry.getUrls(JavadocOrderRootType.getInstance());
    for (String docUrl : docUrls) {
      eclipseUrls.add(EJavadocUtil.toEclipseJavadocPath(myModel, docUrl));
    }

    final List children = new ArrayList(orderEntry.getChildren(EclipseXml.ATTRIBUTES_TAG));
    for (Object o : children) {
      final Element attsElement = (Element)o;
      final ArrayList attTags = new ArrayList(attsElement.getChildren(EclipseXml.ATTRIBUTE_TAG));
      for (Object a : attTags) {
        Element attElement = (Element)a;
        if (Comparing.strEqual(attElement.getAttributeValue("name"), EclipseXml.JAVADOC_LOCATION)) {
          final String javadocPath = attElement.getAttributeValue("value");
          if (!eclipseUrls.remove(javadocPath)) {
            attElement.detach();
          }
        }
      }
    }

    for (final String docUrl : eclipseUrls) {
      Element child = orderEntry.getChild(EclipseXml.ATTRIBUTES_TAG);
      if (child == null) {
        child = new Element(EclipseXml.ATTRIBUTES_TAG);
        orderEntry.addContent(child);
      }

      final Element attrElement = new Element(EclipseXml.ATTRIBUTE_TAG);
      child.addContent(attrElement);
      attrElement.setAttribute("name", EclipseXml.JAVADOC_LOCATION);
      attrElement.setAttribute("value", docUrl);
    }
  }

  private Element addOrderEntry(String kind, String path, Element classpathRoot) {
    final Element element = myOldEntries.get(kind + getJREKey(path));
    if (element != null){
      final Element clonedElement = (Element)element.clone();
      classpathRoot.addContent(clonedElement);
      return clonedElement;
    }
    Element orderEntry = new Element(EclipseXml.CLASSPATHENTRY_TAG);
    orderEntry.setAttribute(EclipseXml.KIND_ATTR, kind);
    if (path != null) {
      orderEntry.setAttribute(EclipseXml.PATH_ATTR, path);
    }
    classpathRoot.addContent(orderEntry);
    return orderEntry;
  }

  private static String getJREKey(String path) {
    return path.startsWith(EclipseXml.JRE_CONTAINER) ? EclipseXml.JRE_CONTAINER : path;
  }

  private static void setExported(Element orderEntry, ExportableOrderEntry dependency) {
    setOrRemoveAttribute(orderEntry, EclipseXml.EXPORTED_ATTR, dependency.isExported() ? EclipseXml.TRUE_VALUE : null);
  }

  private static void setOrRemoveAttribute(Element element, String name, String value) {
    if (value != null) {
      element.setAttribute(name, value);
    }
    else {
      element.removeAttribute(name);
    }
  }

  private static void setAttributeIfAbsent(Element element, String name, String value) {
    if (element.getAttribute(name) == null) {
      element.setAttribute(name, value);
    }
  }

}
