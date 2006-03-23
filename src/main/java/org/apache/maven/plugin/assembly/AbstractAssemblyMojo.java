package org.apache.maven.plugin.assembly;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.IncludesArtifactFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.assembly.filter.AssemblyScopeArtifactFilter;
import org.apache.maven.plugin.assembly.interpolation.AssemblyInterpolationException;
import org.apache.maven.plugin.assembly.interpolation.AssemblyInterpolator;
import org.apache.maven.plugin.assembly.interpolation.ReflectionProperties;
import org.apache.maven.plugin.assembly.utils.PropertyUtils;
import org.apache.maven.plugins.assembly.model.Assembly;
import org.apache.maven.plugins.assembly.model.Component;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.plugins.assembly.model.FileItem;
import org.apache.maven.plugins.assembly.model.FileSet;
import org.apache.maven.plugins.assembly.model.io.xpp3.AssemblyXpp3Reader;
import org.apache.maven.plugins.assembly.model.io.xpp3.ComponentXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.wagon.PathUtils;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.jar.Manifest;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.codehaus.plexus.archiver.tar.TarLongFileMode;
import org.codehaus.plexus.archiver.war.WarArchiver;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.InterpolationFilterReader;
import org.codehaus.plexus.util.introspection.ReflectionValueExtractor;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 */
public abstract class AbstractAssemblyMojo
    extends AbstractUnpackingMojo
{
    /**
     * A list of descriptor files to generate from.
     *
     * @parameter
     */
    private File[] descriptors;

    /**
     * A list of built-in descriptor references to generate from. You can select from <code>bin</code>,
     * <code>jar-with-dependencies</code>, or <code>src</code>.
     *
     * @parameter
     */
    private String[] descriptorRefs;

    /**
     * This is the base directory from which archive files are created.
     * This base directory pre-pended to any <code>&lt;directory&gt;</code>
     * specifications in the assembly descriptor.  This is an optional
     * parameter
     *
     * @parameter
     */
    private File archiveBaseDirectory;

    /**
     * Predefined Assembly Descriptor Id's.  You can select bin, jar-with-dependencies, or src.
     *
     * @parameter expression="${descriptorId}"
     * @deprecated Please use descriptorRefs instead
     */
    protected String descriptorId;

    /**
     * Assembly XML Descriptor file.  This must be the path to your customized descriptor file.
     *
     * @parameter expression="${descriptor}"
     * @deprecated Please use descriptors instead
     */
    protected File descriptor;

    /**
     * Sets the TarArchiver behavior on file paths with more than 100 characters length.
     * Valid values are: "warn" (default), "fail", "truncate", "gnu", or "omit".
     *
     * @parameter expression="${tarLongFileMode}" default-value="warn"
     */
    private String tarLongFileMode;

    /**
     * Base directory of the project.
     *
     * @parameter expression="${basedir}"
     * @required
     * @readonly
     */
    private File basedir;

    /**
     * Maven ProjectHelper
     *
     * @component
     */
    private MavenProjectHelper projectHelper;

    /**
     * Temporary directory that contain the files to be assembled.
     *
     * @parameter expression="${project.build.directory}/archive-tmp"
     * @required
     * @readonly
     */
    private File tempRoot;

    /**
     * Temporary file for line ending translation.
     *
     * @parameter expression="${project.build.directory}/tempFile"
     * @required
     * @readonly
     */
    private File tempFile;

    /**
     * Directory for site generated.
     *
     * @parameter expression="${project.build.directory}/site"
     * @readonly
     */
    private File siteDirectory;

    /**
     * Set to true to include the site generated by site:site goal.
     *
     * @parameter expression="${includeSite}" default-value="false"
     * @deprecated Please set this variable in the assembly descriptor instead
     */
    private boolean includeSite;

    /**
     * Set to false to exclude the assembly id from the assembly final name.
     *
     * @parameter expression="${appendAssemblyId}" default-value="true"
     */
    protected boolean appendAssemblyId;

    private ComponentsXmlArchiverFileFilter componentsXmlFilter = new ComponentsXmlArchiverFileFilter();

    /**
     * @parameter
     */
    private MavenArchiveConfiguration archive;

    /**
     * @parameter expression="${project.build.filters}"
     */
    protected List filters;

    private Properties filterProperties;

    /**
     * Create the binary distribution.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException
     *
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        List assemblies;
        try
        {
            assemblies = readAssemblies();
        }
        catch ( AssemblyInterpolationException e )
        {
            throw new MojoExecutionException( "Failed to interpolate assembly descriptor", e );
        }

        // TODO: include dependencies marked for distribution under certain formats
        // TODO: how, might we plug this into an installer, such as NSIS?

        for ( Iterator i = assemblies.iterator(); i.hasNext(); )
        {
            Assembly assembly = (Assembly) i.next();
            createAssembly( assembly );
        }
    }

    private void createAssembly( Assembly assembly )
        throws MojoExecutionException, MojoFailureException
    {
        String fullName = getDistributionName( assembly );

        for ( Iterator i = assembly.getFormats().iterator(); i.hasNext(); )
        {
            String format = (String) i.next();

            String filename = fullName + "." + format;

            File destFile;
            try
            {
                Archiver archiver = createArchiver( format );

                destFile = createArchive( archiver, assembly, filename );
            }
            catch ( NoSuchArchiverException e )
            {
                throw new MojoFailureException( "Unable to obtain archiver for extension '" + format + "'" );
            }
            catch ( ArchiverException e )
            {
                throw new MojoExecutionException( "Error creating assembly: " + e.getMessage(), e );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Error creating assembly: " + e.getMessage(), e );
            }
            catch ( XmlPullParserException e )
            {
                throw new MojoExecutionException( "Error creating assembly: " + e.getMessage(), e );
            }

            if ( appendAssemblyId )
            {
                projectHelper.attachArtifact( project, format, assembly.getId(), destFile );
            }
            else if ( classifier != null )
            {
                projectHelper.attachArtifact( project, format, classifier, destFile );
            }
            else
            {
                projectHelper.attachArtifact( project, format, null, destFile );
            }
        }
    }

    /**
     * Get the full name of the distribution artifact
     *
     * @param assembly
     * @return the distribution name
     */
    protected String getDistributionName( Assembly assembly )
    {
        String distributionName = finalName;
        if ( appendAssemblyId )
        {
            if ( !StringUtils.isEmpty( assembly.getId() ) )
            {
                distributionName = finalName + "-" + assembly.getId();
            }
        }
        else if ( classifier != null )
        {
            distributionName = finalName + "-" + classifier;
        }
        return distributionName;
    }

    protected File createArchive( Archiver archiver, Assembly assembly, String filename )
        throws ArchiverException, IOException, MojoExecutionException, MojoFailureException, XmlPullParserException
    {
        processDependencySets( archiver, assembly.getDependencySets(), assembly.isIncludeBaseDirectory() );
        processModules( archiver, assembly.getModules(), assembly.isIncludeBaseDirectory() );
        processFileSets( archiver, assembly.getFileSets(), assembly.isIncludeBaseDirectory() );
        processFileList( archiver, assembly.getFiles(), assembly.isIncludeBaseDirectory() );

        componentsXmlFilter.addToArchive( archiver );

        File destFile = new File( outputDirectory, filename );

        if ( archiver instanceof JarArchiver )
        {
            // TODO: I'd really prefer to rewrite MavenArchiver as either a separate manifest creation utility (and to
            // create an include pom.properties etc into another archiver), or an implementation of an archiver
            // (the first is preferable).
            MavenArchiver mavenArchiver = new MavenArchiver();

            if ( archive != null )
            {
                try
                {
                    Manifest manifest = null;
                    File manifestFile = archive.getManifestFile();

                    if ( manifestFile != null )
                    {
                        try
                        {
                            manifest = new Manifest( new FileReader( manifestFile ) );
                        }
                        catch ( FileNotFoundException e )
                        {
                            throw new MojoFailureException( "Manifest not found: " + e.getMessage() );
                        }
                        catch ( IOException e )
                        {
                            throw new MojoExecutionException( "Error processing manifest: " + e.getMessage(), e );
                        }
                    }
                    else
                    {
                        manifest = mavenArchiver.getManifest( project, archive.getManifest() );
                    }

                    if ( manifest != null )
                    {
                        JarArchiver jarArchiver = (JarArchiver) archiver;
                        jarArchiver.addConfiguredManifest( manifest );
                    }
                }
                catch ( ManifestException e )
                {
                    throw new MojoExecutionException( "Error creating manifest: " + e.getMessage(), e );
                }
                catch ( DependencyResolutionRequiredException e )
                {
                    throw new MojoExecutionException( "Dependencies were not resolved: " + e.getMessage(), e );
                }
            }
        }

        archiver.setDestFile( destFile );
        archiver.createArchive();

        return destFile;
    }

    private void processModules( Archiver archiver, List modulesList, boolean includeBaseDirectory )
        throws IOException, ArchiverException, XmlPullParserException
    {
        if ( reactorProjects != null )
        {
            List moduleFileSets = new ArrayList();

            for ( Iterator modules = modulesList.iterator(); modules.hasNext(); )
            {
                String module = (String) modules.next();

                MavenProject reactorProject = getModuleFromReactor( module );

                if ( reactorProject != null )
                {
                    FileSet moduleFileSet = new FileSet();

                    moduleFileSet.setDirectory( reactorProject.getBasedir().getAbsolutePath() );
                    moduleFileSet.setOutputDirectory( module );

                    List excludesList = new ArrayList();
                    excludesList.add( PathUtils.toRelative( reactorProject.getBasedir(), reactorProject.getBuild().getDirectory() ) + "/**" );
                    excludesList.add( PathUtils.toRelative( reactorProject.getBasedir(), reactorProject.getBuild().getOutputDirectory() ) + "/**" );
                    excludesList.add( PathUtils.toRelative( reactorProject.getBasedir(), reactorProject.getBuild().getTestOutputDirectory() ) + "/**" );
                    excludesList.add( PathUtils.toRelative( reactorProject.getBasedir(), reactorProject.getReporting().getOutputDirectory() ) + "/**" );
                    moduleFileSet.setExcludes( excludesList );

                    moduleFileSets.add( moduleFileSet );
                }
            }

            processFileSets( archiver, moduleFileSets, includeBaseDirectory );
        }
    }

    private MavenProject getModuleFromReactor( String module )
    {
        MavenProject reactorProject = null;

        if ( reactorProjects != null )
        {
            File executedProjectDir = getExecutedProject().getFile().getParentFile();
            File moduleDir = new File( executedProjectDir, module );
            String modulePath = moduleDir.getAbsolutePath();

            for ( Iterator projects = reactorProjects.iterator(); projects.hasNext(); )
            {
                MavenProject project = (MavenProject) projects.next();

                String projectPath = project.getFile().getParentFile().getAbsolutePath();

                if ( modulePath.equals( projectPath ) )
                {
                    reactorProject = project;

                    break;
                }
            }
        }

        return reactorProject;
    }

    protected List readAssemblies()
        throws MojoFailureException, MojoExecutionException, AssemblyInterpolationException
    {
        List assemblies = new ArrayList();

        if ( descriptor != null )
        {
            assemblies.add( getAssembly( descriptor ) );
        }

        if ( descriptorId != null )
        {
            assemblies.add( getAssembly( descriptorId ) );
        }

        if ( descriptors != null && descriptors.length > 0 )
        {
            for ( int i = 0; i < descriptors.length; i++ )
            {
                assemblies.add( getAssembly( descriptors[i] ) );
            }
        }

        if ( descriptorRefs != null && descriptorRefs.length > 0 )
        {
            for ( int i = 0; i < descriptorRefs.length; i++ )
            {
                assemblies.add( getAssembly( descriptorRefs[i] ) );
            }
        }

        if ( assemblies.isEmpty() )
        {
            throw new MojoFailureException( "No assembly descriptors found." );
        }

        // check unique IDs
        Set ids = new HashSet();
        for ( Iterator i = assemblies.iterator(); i.hasNext(); )
        {
            Assembly assembly = (Assembly) i.next();
            if ( !ids.add( assembly.getId() ) )
            {
                throw new MojoFailureException( "The assembly id " + assembly.getId() + " is used more than once." );
            }

        }
        return assemblies;
    }

    private Assembly getAssembly( String ref )
        throws MojoFailureException, MojoExecutionException, AssemblyInterpolationException
    {
        InputStream resourceAsStream = getClass().getResourceAsStream( "/assemblies/" + ref + ".xml" );
        if ( resourceAsStream == null )
        {
            throw new MojoFailureException( "Descriptor with ID '" + ref + "' not found" );
        }
        return getAssembly( new InputStreamReader( resourceAsStream ) );
    }

    private Assembly getAssembly( File file )
        throws MojoFailureException, MojoExecutionException, AssemblyInterpolationException
    {
        Reader r;
        try
        {
            r = new FileReader( file );
        }
        catch ( FileNotFoundException e )
        {
            throw new MojoFailureException( "Unable to find descriptor: " + e.getMessage() );
        }

        return getAssembly( r );
    }

    private Assembly getAssembly( Reader reader )
        throws MojoFailureException, MojoExecutionException, AssemblyInterpolationException
    {
        Assembly assembly;

        try
        {
            Map context = new HashMap( System.getProperties() );

            context.put( "basedir", basedir.getAbsolutePath() );

            AssemblyXpp3Reader r = new AssemblyXpp3Reader();
            assembly = r.read( reader );

            assembly = new AssemblyInterpolator().interpolate( assembly, project.getModel(), context );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error reading descriptor", e );
        }
        catch ( XmlPullParserException e )
        {
            throw new MojoExecutionException( "Error reading descriptor", e );
        }
        finally
        {
            IOUtil.close( reader );
        }

        if ( includeSite || assembly.isIncludeSiteDirectory() )
        {
            includeSiteInAssembly( assembly );
        }

        appendComponentsToMainAssembly( assembly );

        return assembly;
    }

    /**
     * Add the contents of all included components to main assembly
     *
     * @param assembly
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */
    private void appendComponentsToMainAssembly( Assembly assembly )
        throws MojoFailureException, MojoExecutionException
    {
        List componentDescriptorFiles = assembly.getComponentDescriptors();

        for ( int i = 0; i < componentDescriptorFiles.size(); ++i )
        {
            Component component = getComponent( componentDescriptorFiles.get( i ).toString() );

            appendComponent( assembly, component );
        }
    }

    /**
     * Add the content of a single Component to main assembly
     *
     * @param assembly
     * @param component
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */
    private void appendComponent( Assembly assembly, Component component )
        throws MojoFailureException, MojoExecutionException
    {
        List dependencySetList = component.getDependencySets();

        for ( int i = 0; i < dependencySetList.size(); ++i )
        {
            assembly.addDependencySet( (DependencySet) dependencySetList.get( i ) );
        }

        List fileSetList = component.getFileSets();

        for ( int i = 0; i < fileSetList.size(); ++i )
        {
            assembly.addFileSet( (FileSet) fileSetList.get( i ) );
        }

        List fileList = component.getFiles();

        for ( int i = 0; i < fileList.size(); ++i )
        {
            assembly.addFile( (FileItem) fileList.get( i ) );
        }
    }

    /**
     * Load the Component via a given file path relative to ${basedir}
     *
     * @param filePath
     * @return
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */

    private Component getComponent( String filePath )
        throws MojoFailureException, MojoExecutionException
    {
        File componentDescriptor = new File( this.project.getBasedir() + "/" + filePath );

        Reader r;
        try
        {
            r = new FileReader( componentDescriptor );
        }
        catch ( FileNotFoundException e )
        {
            throw new MojoFailureException( "Unable to find descriptor: " + e.getMessage() );
        }

        return getComponent( r );

    }

    /**
     * Load the Component via a Reader
     *
     * @param reader
     * @return
     * @throws MojoExecutionException
     */
    private Component getComponent( Reader reader )
        throws MojoExecutionException
    {
        Component component;
        try
        {
            ComponentXpp3Reader r = new ComponentXpp3Reader();
            component = r.read( reader );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error reading component descriptor", e );
        }
        catch ( XmlPullParserException e )
        {
            throw new MojoExecutionException( "Error reading component descriptor", e );
        }
        finally
        {
            IOUtil.close( reader );
        }

        return component;
    }

    /**
     * Processes Dependency Sets
     *
     * @param archiver
     * @param dependencySets
     * @param includeBaseDirectory
     */
    protected void processDependencySets( Archiver archiver, List dependencySets, boolean includeBaseDirectory )
        throws ArchiverException, IOException, MojoExecutionException, MojoFailureException, XmlPullParserException
    {
        for ( Iterator i = dependencySets.iterator(); i.hasNext(); )
        {
            DependencySet dependencySet = (DependencySet) i.next();
            String output = dependencySet.getOutputDirectory();
            output = getOutputDirectory( output, includeBaseDirectory );

            archiver.setDefaultDirectoryMode( Integer.parseInt( dependencySet.getDirectoryMode(), 8 ) );

            archiver.setDefaultFileMode( Integer.parseInt( dependencySet.getFileMode(), 8 ) );

            getLog().debug( "DependencySet[" + output + "]" + " dir perms: " +
                Integer.toString( archiver.getDefaultDirectoryMode(), 8 ) + " file perms: " +
                Integer.toString( archiver.getDefaultFileMode(), 8 ) );

            AndArtifactFilter filter = new AndArtifactFilter();
            filter.add( new AssemblyScopeArtifactFilter( dependencySet.getScope() ) );

            if ( !dependencySet.getIncludes().isEmpty() )
            {
                filter.add( new IncludesArtifactFilter( dependencySet.getIncludes() ) );
            }
            if ( !dependencySet.getExcludes().isEmpty() )
            {
                filter.add( new ExcludesArtifactFilter( dependencySet.getExcludes() ) );
            }

            Set set;

            if ( projectModulesOnly )
            {
                set = getModules();
            }
            else
            {
                set = getDependencies();
            }

            // TODO: includes and excludes
            for ( Iterator j = set.iterator(); j.hasNext(); )
            {
                Artifact artifact = (Artifact) j.next();

                if ( filter.include( artifact ) )
                {
                    String name = artifact.getFile().getName();

                    if ( dependencySet.isUnpack() )
                    {
                        // TODO: something like zipfileset in plexus-archiver
                        //                        archiver.addJar(  )

                        // TODO is the extension always 3 characters long?
                        File tempLocation = new File( workDirectory, name.substring( 0, name.length() - 4 ) );
                        boolean process = false;
                        if ( !tempLocation.exists() )
                        {
                            tempLocation.mkdirs();
                            process = true;
                        }
                        else if ( artifact.getFile().lastModified() > tempLocation.lastModified() )
                        {
                            process = true;
                        }

                        if ( process )
                        {
                            try
                            {
                                unpack( artifact.getFile(), tempLocation );

                                /*
                                 * If the assembly is 'jar-with-dependencies', remove the security files in all dependencies
                                 * that will prevent the uberjar to execute.  Please see MASSEMBLY-64 for details.
                                 */
                                if ( archiver instanceof JarArchiver )
                                {
                                    String[] securityFiles = { "*.RSA", "*.DSA", "*.SF", "*.rsa", "*.dsa", "*.sf" };
                                    org.apache.maven.shared.model.fileset.FileSet securityFileSet = new org.apache.maven.shared.model.fileset.FileSet();
                                    securityFileSet.setDirectory(tempLocation.getAbsolutePath() + "/META-INF/" );

                                    for ( int sfsi = 0; sfsi < securityFiles.length; sfsi++ )
                                    {
                                        securityFileSet.addInclude( securityFiles[sfsi] );
                                    }

                                    FileSetManager fsm = new FileSetManager( getLog() );
                                    try
                                    {
                                        fsm.delete( securityFileSet );
                                    }
                                    catch ( IOException e )
                                    {
                                        throw new MojoExecutionException("Failed to delete security files: " + e.getMessage(), e);
                                    }
                                }
                            }
                            catch ( NoSuchArchiverException e )
                            {
                                throw new MojoExecutionException(
                                    "Unable to obtain unarchiver for file '" + artifact.getFile() + "'" );
                            }
                        }

                        addDirectory( archiver, tempLocation, output, null, FileUtils.getDefaultExcludesAsList() );
                    }
                    else
                    {
                        String outputFileNameMapping = dependencySet.getOutputFileNameMapping();

                        //insert the classifier if exist
                        if ( artifact.getClassifier() != null && !artifact.getClassifier().equals( "" ) )
                        {
                            int dotIdx = outputFileNameMapping.lastIndexOf( "." );

                            String extension =
                                outputFileNameMapping.substring( dotIdx + 1, outputFileNameMapping.length() );
                            String artifactWithoutExt = outputFileNameMapping.substring( 0, dotIdx );

                            outputFileNameMapping =
                                artifactWithoutExt + "-" + artifact.getClassifier() + "." + extension;
                        }
                        archiver.addFile( artifact.getFile(),
                                          output + evaluateFileNameMapping( outputFileNameMapping, artifact ) );
                    }
                }
                else
                {
                    // would be better to have a way to find out when a specified include or exclude
                    // is never triggered and warn() it.
                    getLog().debug( "artifact: " + artifact + " not included" );
                }
            }
        }
    }

    private void addDirectory( Archiver archiver, File directory, String output, String[] includes, List excludes )
        throws IOException, XmlPullParserException, ArchiverException
    {
        if ( directory.exists() )
        {
            List adaptedExcludes = excludes;

            // TODO: more robust set of filters on added files in the archiver
            File componentsXml = new File( directory, ComponentsXmlArchiverFileFilter.COMPONENTS_XML_PATH );
            if ( componentsXml.exists() )
            {
                componentsXmlFilter.addComponentsXml( componentsXml );
                adaptedExcludes = new ArrayList( excludes );
                adaptedExcludes.add( ComponentsXmlArchiverFileFilter.COMPONENTS_XML_PATH );
            }

            archiver.addDirectory( directory, output, includes,
                                   (String[]) adaptedExcludes.toArray( EMPTY_STRING_ARRAY ) );
        }
    }

    /**
     * Process Files that will be included in the distribution.
     *
     * @param archiver
     * @param fileSets
     * @param includeBaseDirecetory
     * @throws org.codehaus.plexus.archiver.ArchiverException
     *
     */
    protected void processFileSets( Archiver archiver, List fileSets, boolean includeBaseDirecetory )
        throws ArchiverException, IOException, XmlPullParserException
    {
        for ( Iterator i = fileSets.iterator(); i.hasNext(); )
        {
            FileSet fileSet = (FileSet) i.next();
            String directory = fileSet.getDirectory();
            String output = fileSet.getOutputDirectory();

            String lineEnding = getLineEndingCharacters( fileSet.getLineEnding() );

            File tmpDir = null;

            if ( lineEnding != null )
            {
                tmpDir = FileUtils.createTempFile( "", "", tempRoot );
                tmpDir.mkdirs();
            }

            archiver.setDefaultDirectoryMode( Integer.parseInt( fileSet.getDirectoryMode(), 8 ) );

            archiver.setDefaultFileMode( Integer.parseInt( fileSet.getFileMode(), 8 ) );

            getLog()
                .debug( "FileSet[" + output + "]" + " dir perms: " +
                    Integer.toString( archiver.getDefaultDirectoryMode(), 8 ) + " file perms: " +
                    Integer.toString( archiver.getDefaultFileMode(), 8 ) +
                    ( fileSet.getLineEnding() == null ? "" : " lineEndings: " + fileSet.getLineEnding() ) );

            if ( directory == null )
            {
                directory = basedir.getAbsolutePath();
                if ( output == null )
                {
                    output = "";
                }
            }
            else
            {
                if ( output == null )
                {
                    output = directory;
                }
            }
            output = getOutputDirectory( output, includeBaseDirecetory );

            String[] includes = (String[]) fileSet.getIncludes().toArray( EMPTY_STRING_ARRAY );
            if ( includes.length == 0 )
            {
                includes = null;
            }

            // TODO: default excludes should be in the archiver?
            List excludesList = fileSet.getExcludes();
            excludesList.addAll( FileUtils.getDefaultExcludesAsList() );

            String[] excludes = (String[]) excludesList.toArray( EMPTY_STRING_ARRAY );

            File archiveBaseDir;
            if ( archiveBaseDirectory == null )
            {
                archiveBaseDir = new File( directory );
            }
            else
            {
                if ( ! archiveBaseDirectory.exists() )
                {
                    throw new IOException(
                        "The archive base directory '" + archiveBaseDirectory.getAbsolutePath() + "' does not exist" );
                }
                if ( ! archiveBaseDirectory.isDirectory() )
                {
                    throw new IOException( "The archive base directory '" + archiveBaseDirectory.getAbsolutePath() +
                        "' exists, but it is not a directory" );
                }
                archiveBaseDir = new File( archiveBaseDirectory, directory );
            }
            getLog().debug( "The archive base directory is '" + archiveBaseDir.getAbsolutePath() + "'" );

            if ( ! archiveBaseDir.isAbsolute() )
            {
                archiveBaseDir = new File( basedir, directory );
            }

            if ( lineEnding != null )
            {
                copySetReplacingLineEndings( archiveBaseDir, tmpDir, includes, excludes, lineEnding );

                archiveBaseDir = tmpDir;
            }
            getLog().debug( "Archive base directory: '" + archiveBaseDir.getAbsolutePath() + "'" );
            addDirectory( archiver, archiveBaseDir, output, includes, excludesList );
        }
    }

    /**
     * Copy files to the distribution with option to change destination name
     *
     * @param archiver
     * @param fileList
     * @throws org.codehaus.plexus.archiver.ArchiverException
     *
     */
    protected void processFileList( Archiver archiver, List fileList, boolean includeBaseDirecetory )
        throws ArchiverException, IOException, MojoExecutionException
    {
        File source = null;
        File filteredFile = null;
        File sourceFileItem = null;

        for ( Iterator i = fileList.iterator(); i.hasNext(); )
        {
            FileItem fileItem = (FileItem) i.next();

            if( fileItem.isFiltered() )
            {
                sourceFileItem = new File( fileItem.getSource() );

                try
                {
                    filteredFile = filterFile( sourceFileItem );

                    fileItem.setSource( filteredFile.getAbsolutePath() );
                }
                catch( Exception e )
                {
                    throw new MojoExecutionException( "Failed to interpolate resource " + sourceFileItem.getName(), e );
                }
            }

            String outputDirectory = fileItem.getOutputDirectory();

            source = new File( fileItem.getSource() );

            if ( outputDirectory == null )
            {
                outputDirectory = "";
            }

            String destName = fileItem.getDestName();

            if ( destName == null )
            {
                destName = source.getName();
            }

            String lineEnding = getLineEndingCharacters( fileItem.getLineEnding() );

            if ( lineEnding != null )
            {
                this.copyReplacingLineEndings( source, this.tempFile, lineEnding );
                source = this.tempFile;
            }

            outputDirectory = getOutputDirectory( outputDirectory, includeBaseDirecetory );

            // omit the last char if ends with / or \\
            if( outputDirectory.endsWith( "/" ) || outputDirectory.endsWith( "\\" ) )
            {
                outputDirectory = outputDirectory.substring( 0, outputDirectory.length() - 1 );
            }

            archiver.addFile( source, outputDirectory + "/" + destName, Integer.parseInt( fileItem.getFileMode() ) );

            // return to original source
            if( fileItem.isFiltered() )
            {
                fileItem.setSource( sourceFileItem.getAbsolutePath() );
            }
        }
    }

    /**
     * Evaluates Filename Mapping
     *
     * @param expression
     * @param artifact
     * @return expression
     * @throws org.apache.maven.plugin.MojoExecutionException
     *
     */
    private static String evaluateFileNameMapping( String expression, Artifact artifact )
        throws MojoExecutionException
    {
        String value = expression;

        // this matches the last ${...} string
        Pattern pat = Pattern.compile( "^(.*)\\$\\{([^\\}]+)\\}(.*)$" );
        Matcher mat = pat.matcher( expression );

        if ( mat.matches() )
        {
            Object middle;
            String left = evaluateFileNameMapping( mat.group( 1 ), artifact );
            try
            {
                middle = ReflectionValueExtractor.evaluate( "dep." + mat.group( 2 ), artifact );
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Cannot evaluate filenameMapping", e );
            }
            String right = mat.group( 3 );

            if ( middle == null )
            {
                // TODO: There should be a more generic way dealing with that. Having magic words is not good at all.
                // probe for magic word
                if ( "extension".equals( mat.group( 2 ).trim() ) )
                {
                    ArtifactHandler artifactHandler = artifact.getArtifactHandler();
                    middle = artifactHandler.getExtension();
                }
                else
                {
                    middle = "${" + mat.group( 2 ) + "}";
                }
            }

            value = left + middle + right;
        }

        return value;
    }

    /**
     * Get the Output Directory by parsing the String output directory.
     *
     * @param output               The string representation of the output directory.
     * @param includeBaseDirectory True if base directory is to be included in the assembled file.
     */
    private String getOutputDirectory( String output, boolean includeBaseDirectory )
    {
        String value = output;
        if ( value == null )
        {
            value = "";
        }
        if ( !value.endsWith( "/" ) && !value.endsWith( "\\" ) )
        {
            // TODO: shouldn't archiver do this?
            value += '/';
        }

        if ( includeBaseDirectory )
        {
            if ( value.startsWith( "/" ) )
            {
                value = finalName + value;
            }
            else
            {
                value = finalName + "/" + value;
            }
        }
        else
        {
            if ( value.startsWith( "/" ) )
            {
                value = value.substring( 1 );
            }
        }
        return value;
    }

    /**
     * Creates the necessary archiver to build the distribution file.
     *
     * @param format Archive format
     * @return archiver  Archiver generated
     * @throws org.codehaus.plexus.archiver.ArchiverException
     *
     * @throws org.codehaus.plexus.archiver.manager.NoSuchArchiverException
     *
     */
    private Archiver createArchiver( String format )
        throws ArchiverException, NoSuchArchiverException
    {
        Archiver archiver;
        if ( format.startsWith( "tar" ) )
        {
            TarArchiver tarArchiver = (TarArchiver) this.archiverManager.getArchiver( "tar" );
            archiver = tarArchiver;
            int index = format.indexOf( '.' );
            if ( index >= 0 )
            {
                // TODO: this needs a cleanup in plexus archiver - use a real typesafe enum
                TarArchiver.TarCompressionMethod tarCompressionMethod = new TarArchiver.TarCompressionMethod();
                // TODO: this should accept gz and bz2 as well so we can skip over the switch
                String compression = format.substring( index + 1 );
                if ( "gz".equals( compression ) )
                {
                    tarCompressionMethod.setValue( "gzip" );
                }
                else if ( "bz2".equals( compression ) )
                {
                    tarCompressionMethod.setValue( "bzip2" );
                }
                else
                {
                    // TODO: better handling
                    throw new IllegalArgumentException( "Unknown compression format: " + compression );
                }
                tarArchiver.setCompression( tarCompressionMethod );

                tarArchiver.setLongfile( getTarLongFileMode() );
            }
        }
        else if ( "war".equals( format ) )
        {
            WarArchiver warArchiver = (WarArchiver) this.archiverManager.getArchiver( "war" );
            warArchiver.setIgnoreWebxml( false ); // See MNG-1274
            archiver = warArchiver;
        }
        else
        {
            archiver = this.archiverManager.getArchiver( format );
        }
        return archiver;
    }

    private TarLongFileMode getTarLongFileMode()
        throws ArchiverException
    {
        TarLongFileMode tarFileMode = new TarLongFileMode();

        tarFileMode.setValue( tarLongFileMode );

        return tarFileMode;
    }

    private void copyReplacingLineEndings( File source, File dest, String lineEndings )
        throws IOException
    {
        getLog().debug( "Copying while replacing line endings: " + source + " to " + dest );

        BufferedReader in = new BufferedReader( new FileReader( source ) );
        BufferedWriter out = new BufferedWriter( new FileWriter( dest ) );

        String line;

        do
        {
            line = in.readLine();
            if ( line != null )
            {
                out.write( line );
                out.write( lineEndings );
            }
        }
        while ( line != null );

        out.flush();
        out.close();
    }

    private void copySetReplacingLineEndings( File archiveBaseDir, File tmpDir, String[] includes, String[] excludes,
                                              String lineEnding )
        throws ArchiverException
    {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir( archiveBaseDir.getAbsolutePath() );
        scanner.setIncludes( includes );
        scanner.setExcludes( excludes );
        scanner.scan();

        String[] dirs = scanner.getIncludedDirectories();

        for ( int j = 0; j < dirs.length; j++ )
        {
            new File( tempRoot, dirs[j] ).mkdirs();
        }

        String[] files = scanner.getIncludedFiles();

        for ( int j = 0; j < files.length; j++ )
        {
            File targetFile = new File( tmpDir, files[j] );

            try
            {
                targetFile.getParentFile().mkdirs();

                copyReplacingLineEndings( new File( archiveBaseDir, files[j] ), targetFile, lineEnding );
            }
            catch ( IOException e )
            {
                throw new ArchiverException( "Error copying file '" + files[j] + "' to '" + targetFile + "'", e );
            }
        }

    }

    private static String getLineEndingCharacters( String lineEnding )
        throws ArchiverException
    {
        String value = lineEnding;
        if ( lineEnding != null )
        {
            if ( "keep".equals( lineEnding ) )
            {
                value = null;
            }
            else if ( "dos".equals( lineEnding ) || "crlf".equals( lineEnding ) )
            {
                value = "\r\n";
            }
            else if ( "unix".equals( lineEnding ) || "lf".equals( lineEnding ) )
            {
                value = "\n";
            }
            else
            {
                throw new ArchiverException( "Illlegal lineEnding specified: '" + lineEnding + "'" );
            }
        }

        return value;
    }

    private void includeSiteInAssembly( Assembly assembly )
        throws MojoExecutionException
    {
        if ( !siteDirectory.exists() )
        {
            throw new MojoExecutionException(
                "site did not exist in the target directory - please run site:site before creating the assembly" );
        }

        getLog().info( "Adding site directory to assembly : " + siteDirectory );

        FileSet siteFileSet = new FileSet();

        siteFileSet.setDirectory( siteDirectory.getPath() );

        siteFileSet.setOutputDirectory( "/site" );

        assembly.addFileSet( siteFileSet );
    }
    private void initializeFiltering()
        throws MojoExecutionException
    {
        getLog().info( "Initializing assembly filters..." );

        // System properties
        filterProperties = new Properties( System.getProperties() );

        // Project properties
        filterProperties.putAll( project.getProperties() );

        if( filters != null && !filters.isEmpty() )
        {
            for ( Iterator i = filters.iterator(); i.hasNext(); )
            {
                String filtersfile = (String) i.next();

                try
                {
                    Properties properties = PropertyUtils.loadPropertyFile( new File( filtersfile ), true, true );

                    filterProperties.putAll( properties );
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "Error loading property file '" + filtersfile + "'", e );
                }
            }
        }
    }

    private File filterFile( File file )
        throws IOException, MojoExecutionException
    {
        initializeFiltering();

        BufferedReader fileReader = new BufferedReader( new FileReader( file ) );
        //Writer fileWriter = new FileWriter( file );

        // support ${token}
        Reader reader = new InterpolationFilterReader( fileReader, filterProperties, "${", "}" );

        boolean isPropertiesFile = false;

        if ( file.isFile() && file.getName().endsWith( ".properties" ) )
        {
            isPropertiesFile = true;
        }
        reader = new InterpolationFilterReader( reader, new ReflectionProperties( project, isPropertiesFile ), "${", "}" );

        File tempFilterFile = new File( tempRoot + "/" + file.getName() );

        tempFilterFile.getParentFile().mkdirs();
        tempFilterFile.createNewFile();

        Writer fileWriter = new FileWriter( tempFilterFile );

        String line = null;

        BufferedReader in = new BufferedReader( reader );

        while( ( line = in.readLine() ) != null )
        {
            fileWriter.write( line );
            fileWriter.write( System.getProperty( "line.separator" ) );
        }

        fileWriter.flush();
        fileWriter.close();
        in.close();
        fileReader.close();

        return tempFilterFile;
    }
}