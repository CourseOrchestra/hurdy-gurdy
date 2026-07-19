/*
 * Copyright 2026 Ivan Ponomarev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.curs.hurdygurdy;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Mojo(
        name = "gen-server",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES
)
public class CodegenMojo extends AbstractMojo {

    @Parameter(property = "language", defaultValue = "java")
    String language;

    @Parameter(property = "framework", defaultValue = "spring")
    String framework;

    @Parameter(property = "javaDtoStyle", defaultValue = "lombok")
    String javaDtoStyle;

    @Parameter(property = "generate", defaultValue = "controller")
    String generate;

    @Parameter(property = "spec", required = true)
    String spec;

    @Parameter(property = "rootPackage", required = true)
    String rootPackage;

    @Parameter(property = "generateResponseParameter", required = false)
    boolean generateResponseParameter = false;

    /**
     * @deprecated superseded by adding {@code api} to the {@code generate} parameter
     */
    @Deprecated
    @Parameter(property = "generateApiInterface", required = false)
    boolean generateApiInterface = false;

    @Parameter(property = "forceSnakeCaseForProperties", required = false)
    boolean forceSnakeCaseForProperties = true;

    @Parameter(property = "generateAliasAsModel", required = false)
    boolean generateAliasAsModel = false;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    PluginDescriptor pluginDescriptor;

    @Parameter(
            defaultValue = "${project.build.directory}/generated-sources/openapi",
            property = "outputDirectory")
    File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        Set<Role> roles = Role.parse(generate);
        if (generateApiInterface) {
            getLog().warn("generateApiInterface is deprecated; use <generate>controller,api</generate> instead");
            roles.add(Role.API);
        }
        GeneratorParams params =
                GeneratorParams.rootPackage(rootPackage)
                        .generateResponseParameter(generateResponseParameter)
                        .forceSnakeCaseForProperties(forceSnakeCaseForProperties)
                        .generateAliasAsModel(generateAliasAsModel)
                        .framework(Framework.of(framework))
                        .javaDtoStyle(JavaDtoStyle.of(javaDtoStyle))
                        .generate(roles);
        Codegen<?> codegen =
                "java".equalsIgnoreCase(language)
                        ? new JavaCodegen(params)
                        : new KotlinCodegen(params);
        try {
            Path targetPath = outputDirectory.toPath();
            Files.createDirectories(targetPath);
            Fingerprint fingerprint = new Fingerprint(this);
            if (fingerprint.changed()) {
                codegen.generate(Path.of(spec), targetPath);
                fingerprint.save();
            } else {
                getLog().info("hurdy-gurdy: generated sources are up to date, skipping generation");
            }
            project.addCompileSourceRoot(targetPath.toString());
        } catch (IOException e) {
            throw new MojoExecutionException("Generation failed", e);
        }
    }

}
