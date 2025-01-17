


/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.assembly.filter;

import javax.inject.Named;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.input.XmlStreamReader;
import org.apache.commons.io.output.XmlStreamWriter;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.ResourceIterator;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.components.io.fileselectors.FileInfo;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * <code>plexus</code>: Plexus Components XML file filter <code>META-INF/plexus/components.xml</code>.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 *
 */
@Named("plexus")
public class ComponentsXmlArchiverFileFilter implements ContainerDescriptorHandler {
    public static final String COMPONENTS_XML_PATH = "META-INF/plexus/components.xml";

    // [jdcasey] Switched visibility to protected to allow testing. Also, because this class isn't final, it should
    // allow
    // some minimal access to the components accumulated for extending classes.
    Map<String, Xpp3Dom> components;

    private boolean excludeOverride = false;

    public void addComponentsXml(final Reader componentsReader) throws XmlPullParserException, IOException {
        Xpp3Dom newDom = Xpp3DomBuilder.build(componentsReader);

        if (newDom != null) {
            newDom = newDom.getChild("components");
        }

        if (newDom != null) {
            final Xpp3Dom[] children = newDom.getChildren();

            for (final Xpp3Dom component : children) {
                if (components == null) {
                    components = new LinkedHashMap<>();
                }

                final String role = component.getChild("role").getValue();
                final Xpp3Dom child = component.getChild("role-hint");
                final String roleHint = child != null ? child.getValue() : "";

                final String key = role + roleHint;
                if (!components.containsKey(key)) {
                    components.put(key, component);
                }
            }
        }
    }

    private void addToArchive(final Archiver archiver) throws IOException {
        if (components != null) {
            final File f = Files.createTempFile("maven-assembly-plugin", "tmp").toFile();
            f.deleteOnExit();

            try (Writer fileWriter = new XmlStreamWriter(Files.newOutputStream(f.toPath()))) {
                final Xpp3Dom dom = new Xpp3Dom("component-set");
                final Xpp3Dom componentDom = new Xpp3Dom("components");
                dom.addChild(componentDom);

                for (final Xpp3Dom component : components.values()) {
                    componentDom.addChild(component);
                }

                Xpp3DomWriter.write(fileWriter, dom);
            }

            excludeOverride = true;

            archiver.addFile(f, COMPONENTS_XML_PATH);

            excludeOverride = false;
        }
    }

    @Override
    public void finalizeArchiveCreation(final Archiver archiver) {
        // this will prompt the isSelected() call, below, for all resources added to the archive.
        // FIXME: This needs to be corrected in the AbstractArchiver, where
        // runArchiveFinalizers() is called before regular resources are added...
        // which is done because the manifest needs to be added first, and the
        // manifest-creation component is a finalizer in the assembly plugin...
        for (final ResourceIterator it = archiver.getResources(); it.hasNext(); ) {
            it.next();
        }

        try {
            addToArchive(archiver);
        } catch (final IOException e) {
            throw new ArchiverException("Error finalizing component-set for archive. Reason: " + e.getMessage(), e);
        }
    }

    @Override
    public void addToArchive(org.apache.commons.compress.archivers.examples.Archiver archiver) {

    }

    @Override
    public List<String> getVirtualFiles() {
        if ((components != null) && !components.isEmpty()) {
            return Collections.singletonList(COMPONENTS_XML_PATH);
        }

        return null;
    }

    @Override
    public boolean isSelected(final FileInfo fileInfo) throws IOException {
        if (fileInfo.isFile()) {
            if (excludeOverride) {
                return true;
            }

            String entry = fileInfo.getName().replace('\\', '/');

            if (entry.startsWith("/")) {
                entry = entry.substring(1);
            }

            if (ComponentsXmlArchiverFileFilter.COMPONENTS_XML_PATH.equals(entry)) {
                try (Reader reader = new BufferedReader(new XmlStreamReader(fileInfo.getContents()))) {
                    addComponentsXml(reader);
                } catch (final XmlPullParserException e) {
                    final IOException error =
                            new IOException("Error finalizing component-set for archive. Reason: " + e.getMessage(), e);

                    throw error;
                }
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    @Override
    public void finalizeArchiveExtraction(final UnArchiver unarchiver) {}
}
