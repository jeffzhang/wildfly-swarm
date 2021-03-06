package org.wildfly.swarm.plugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.inject.Inject;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.ArtifactResolver;

/**
 * @author Bob McWhirter
 * @author Ken Finnigan
 */
@Mojo(
        name = "create",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class CreateMojo extends AbstractSwarmMojo {

    private static final String MAIN_SLOT = "main";

    @Inject
    private ArtifactResolver resolver;

    private Map<String, List<String>> modules = new HashMap<>();

    private List<String> fractionModules = new ArrayList<>();

    @Parameter(alias="modules")
    private String[] additionalModules;

    private Path dir;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        setupFeaturePacks(this.resolver);
        setupFeaturePackArtifacts();
        setupDirectory();
        addJBossModules();
        addBootstrap();

        processFractions(this.resolver, new FractionExpander());
        addAdditionalModules();

        addMavenRepository();

        addProjectArtifact();
        addProjectDependenciesToRepository();
        createJar();
    }

    private void setupFeaturePackArtifacts() throws MojoFailureException {
        for (Artifact pack : this.featurePacks) {
            Artifact packPom = new DefaultArtifact(pack.getGroupId(),
                    pack.getArtifactId(),
                    pack.getVersion(),
                    "",
                    "pom",
                    pack.getClassifier(),
                    new DefaultArtifactHandler("pom"));

            try {
                ProjectBuildingResult buildingResult = buildProject(packPom);
                DependencyResolutionResult resolutionResult = resolveProjectDependencies(buildingResult.getProject(), null);

                if (resolutionResult.getDependencies() != null && resolutionResult.getDependencies().size() > 0) {
                    for (Dependency dep : resolutionResult.getDependencies()) {
                        this.featurePackArtifacts.add(convertAetherToMavenArtifact(dep.getArtifact(), "compile", "jar"));
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }
    }

    private void addMavenRepository() throws MojoFailureException {
        Path modulesDir = this.dir.resolve("modules");

        analyzeModuleXmls(modulesDir);
        collectArtifacts();
    }

    private void collectArtifacts() throws MojoFailureException {
        for (ArtifactSpec each : this.gavs) {
            if (!collectArtifact(each)) {
                getLog().error("unable to locate artifact: " + each);
            }
        }
    }

    private boolean collectArtifact(ArtifactSpec spec) throws MojoFailureException {
        Artifact artifact = locateArtifact(spec);
        if (artifact == null) {
            return false;
        }

        addArtifact(artifact);
        return true;
    }

    private void addArtifact(Artifact artifact) throws MojoFailureException {
        Path m2repo = this.dir.resolve("m2repo");
        Path dest = m2repo.resolve(ArtifactUtils.toPath(artifact));

        try {
            Files.createDirectories(dest.getParent());
            Files.copy(artifact.getFile().toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoFailureException("unable to add artifact: " + dest, e);
        }
    }

    private Artifact locateArtifact(ArtifactSpec spec) {
        for (Artifact each : this.featurePackArtifacts) {
            if (spec.matches(each)) {
                return each;
            }
        }

        for (Artifact each : this.pluginArtifacts) {
            if (spec.matches(each)) {
                return each;
            }
        }

        for (Artifact each : this.project.getArtifacts()) {
            if (spec.matches(each)) {
                return each;
            }
        }

        return null;
    }

    private void analyzeModuleXmls(final Path path) throws MojoFailureException {
        if (Files.isDirectory(path)) {
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>(){
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        try {
                            analyzeModuleXmls(file);
                        } catch (MojoFailureException e) {
                            throw new IOException(e);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new MojoFailureException("Failed to analyze module XML for " + path, e);
            }
        } else if ("module.xml".equals(path.getFileName().toString())) {
            analyzeModuleXml(path);
        }
    }

    private void analyzeModuleXml(Path path) throws MojoFailureException {
        try {
            final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                int start = line.indexOf("${");
                if (start >= 0) {
                    int end = line.indexOf("}");
                    if (end > 0) {
                        String gav = line.substring(start + 2, end);
                        this.gavs.add(new ArtifactSpec(gav));
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoFailureException("Unable to analyze: " + path, e);
        }
    }

    private void addTransitiveModules(Path moduleXml) {
        try (BufferedReader in = Files.newBufferedReader(moduleXml, StandardCharsets.UTF_8)) {

            String line = null;

            while ((line = in.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("<module-alias")) {
                    int start = line.indexOf(TARGET_NAME_PREFIX);
                    if (start > 0) {
                        int end = line.indexOf("\"", start + TARGET_NAME_PREFIX.length());
                        if (end >= 0) {
                            String moduleName = line.substring(start + TARGET_NAME_PREFIX.length(), end);
                            addTransitiveModule(moduleName);
                            break;
                        }
                    }
                }

                if (line.startsWith("<module name=")) {

                    int start = line.indexOf("\"");
                    if (start > 0) {
                        int end = line.indexOf("\"", start + 1);
                        if (end > 0) {
                            String moduleName = line.substring(start + 1, end);
                            if (!line.contains("optional=\"true\"")) {
                                int slotStart = line.indexOf("slot=\"");
                                if (slotStart > 0) {
                                    slotStart += 6;
                                    int slotEnd = line.indexOf("\"", slotStart + 1);
                                    String slotName = line.substring(slotStart, slotEnd);
                                    addTransitiveModule(moduleName, slotName);
                                } else {
                                    addTransitiveModule(moduleName);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            getLog().error(e);
        }
    }

    private void addTransitiveModule(String moduleName) {
        addTransitiveModule(moduleName, MAIN_SLOT);
    }

    private void addTransitiveModule(String moduleName, String slot) {
        if (this.modules.containsKey(moduleName) && this.modules.get(moduleName).contains(slot)) {
            return;
        }

        String search = "modules/system/layers/base/" + moduleName.replace('.', '/') + "/" + slot + "/module.xml";

        for (Artifact pack : this.featurePacks) {
            try {
                ZipFile zip = new ZipFile(pack.getFile());
                Enumeration<? extends ZipEntry> entries = zip.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry each = entries.nextElement();

                    if (each.getName().equals(search)) {
                        Path outFile = this.dir.resolve(search);
                        Files.createDirectories(outFile);
                        copyFileFromZip(zip, each, outFile);
                        List<String> slots = new ArrayList<>();
                        slots.add(slot);
                        this.modules.put(moduleName, slots);
                        addTransitiveModules(outFile);
                        return;
                    }
                }
            } catch (IOException e) {
                getLog().error(e);
            }
        }
    }

    private void addAdditionalModules() {
        System.err.println( "adding additional modules: " + Arrays.asList( this.additionalModules ) );
        if ( this.additionalModules == null ) {
            return;
        }
        for ( int i = 0 ; i < this.additionalModules.length ; ++i ) {
            addTransitiveModule( this.additionalModules[i] );
        }

    }


    private void setupDirectory() throws MojoFailureException {
        this.dir = Paths.get(this.projectBuildDir, "wildfly-swarm-archive");
        try {
            if (Files.notExists(dir)) {
                Files.createDirectories(dir);
            } else {
                emptyDir(dir);
            }
        } catch (IOException e) {
            throw new MojoFailureException("Failed to setup wildfly-swarm-archive directory", e);
        }
    }

    private void emptyDir(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addJBossModules() throws MojoFailureException {
        Artifact artifact = findArtifact("org.jboss.modules", "jboss-modules", "jar");
        try {
            expandArtifact(artifact);
        } catch (IOException e) {
            throw new MojoFailureException("Unable to add jboss-modules");
        }
    }


    private void expandArtifact(Artifact artifact) throws IOException {

        Path destination = this.dir;

        File artifactFile = artifact.getFile();

        if (artifact.getType().equals("jar")) {
            JarFile jarFile = new JarFile(artifactFile);
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry each = entries.nextElement();
                if (each.getName().startsWith("META-INF")) {
                    continue;
                }
                Path fsEach = destination.resolve(each.getName());
                if (each.isDirectory()) {
                    Files.createDirectories(fsEach);
                } else {
                    copyFileFromZip(jarFile, each, fsEach);
                }
            }
        }
    }

    private Artifact findArtifact(String groupId, String artifactId, String type) {
        Set<Artifact> artifacts = this.project.getArtifacts();

        for (Artifact each : artifacts) {
            if (each.getGroupId().equals(groupId) && each.getArtifactId().equals(artifactId) && each.getType().equals(type)) {
                return each;
            }
        }

        return null;
    }

    private void addBootstrap() throws MojoFailureException {
        Artifact artifact = findArtifact("org.wildfly.swarm", "wildfly-swarm-bootstrap", "jar");
        try {
            expandArtifact(artifact);
        } catch (IOException e) {
            throw new MojoFailureException("Unable to add bootstrap", e);
        }
    }

    private void addProjectArtifact() throws MojoFailureException {
        Artifact artifact = this.project.getArtifact();

        Path appDir = this.dir.resolve("app");
        Path appFile = appDir.resolve(artifact.getFile().getName());

        try {
            Files.createDirectories(appDir);
            Files.copy(artifact.getFile().toPath(), appFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoFailureException("Error copying project artifact", e);
        }
    }

    private void addProjectDependenciesToRepository() throws MojoFailureException {
        if ( ! this.project.getPackaging().equals( "jar" ) ) {
            return;
        }

        Path depsTxt = this.dir.resolve("dependencies.txt");
        try (final BufferedWriter out = Files.newBufferedWriter(depsTxt, StandardCharsets.UTF_8)){
            Set<Artifact> dependencies = this.project.getArtifacts();

            for (Artifact each : dependencies) {
                String scope = each.getScope();
                if (scope.equals("compile") || scope.equals("runtime")) {
                    addArtifact(each);
                    out.write( each.getGroupId() + ":" + each.getArtifactId() + ":" + each.getVersion() + "\n" );
                }
            }
        } catch (IOException e) {
            throw new MojoFailureException("Unable to create dependencies.txt", e);
        }

    }

    private void createJar() throws MojoFailureException {

        Artifact primaryArtifact = this.project.getArtifact();

        ArtifactHandler handler = new DefaultArtifactHandler("jar");
        Artifact artifact = new DefaultArtifact(
                primaryArtifact.getGroupId(),
                primaryArtifact.getArtifactId(),
                primaryArtifact.getVersion(),
                primaryArtifact.getScope(),
                "jar",
                "swarm",
                handler
        );


        String name = artifact.getArtifactId() + "-" + artifact.getVersion() + "-swarm.jar";

        File file = new File(this.projectBuildDir, name);

        Manifest manifest = createManifest();
        try (
                FileOutputStream fileOut = new FileOutputStream(file);
                JarOutputStream out = new JarOutputStream(fileOut, manifest)
        ) {
            writeToJar(out, this.dir);
        } catch (IOException e) {
            throw new MojoFailureException("Unable to create jar", e);
        }

        artifact.setFile(file);

        this.project.addAttachedArtifact(artifact);
    }

    private void writeToJar(final JarOutputStream out, final Path entry) throws IOException {
        String rootPath = this.dir.toAbsolutePath().toString();
        String entryPath = entry.toAbsolutePath().toString();

        if (!rootPath.equals(entryPath)) {
            String jarPath = entryPath.substring(rootPath.length() + 1);
            if (Files.isDirectory(entry)) {
                jarPath = jarPath + "/";
            }
            out.putNextEntry(new ZipEntry(jarPath));
        }

        if (Files.isDirectory(entry)) {
            Files.walkFileTree(entry, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    writeToJar(out, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.copy(entry, out);
        }
    }


    private Manifest createManifest() throws MojoFailureException {
        Manifest manifest = new Manifest();

        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "org.wildfly.swarm.bootstrap.Main");
        attrs.putValue("Application-Artifact", this.project.getArtifact().getFile().getName());

        StringBuilder modules = new StringBuilder();
        boolean first = true;

        for (String each : this.fractionModules) {
            if (!first) {
                modules.append(",");
            }

            modules.append(each);
            first = false;
        }
        attrs.putValue("Feature-Pack-Modules", modules.toString());

        return manifest;
    }

    final class FractionExpander implements ExceptionConsumer<org.eclipse.aether.artifact.Artifact> {
        @Override
        public void accept(org.eclipse.aether.artifact.Artifact artifact) throws Exception {
            ZipFile zipFile = new ZipFile(artifact.getFile());
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry each = entries.nextElement();

                Path fsEach = dir.resolve(each.getName());

                if (each.isDirectory()) {
                    Files.createDirectories(fsEach);
                    continue;
                }

                if (each.getName().startsWith(MODULE_PREFIX) && each.getName().endsWith(MODULE_SUFFIX)) {
                    String moduleName = each.getName().substring(MODULE_PREFIX.length(), each.getName().length() - MODULE_SUFFIX.length());

                    moduleName = moduleName.replace('/', '.') + ":main";
                    fractionModules.add(moduleName);
                }

                copyFileFromZip(zipFile, each, fsEach);

                if ("module.xml".equals(fsEach.getFileName().toString())) {
                    addTransitiveModules(fsEach);
                }
            }
        }
    }

}
