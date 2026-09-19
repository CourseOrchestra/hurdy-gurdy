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

package ru.curs.hurdygurdy.extract;

import ru.curs.hurdygurdy.ClassCategory;
import ru.curs.hurdygurdy.Framework;
import ru.curs.hurdygurdy.GeneratorParams;
import ru.curs.hurdygurdy.Role;
import ru.curs.hurdygurdy.TypeSpecExtractor;
import ru.curs.hurdygurdy.model.ApiModelBuilder;
import ru.curs.hurdygurdy.model.InterfaceModel;
import ru.curs.hurdygurdy.model.OperationModel;
import io.swagger.v3.oas.models.OpenAPI;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Turns the API model into one generated interface per tag.
 *
 * <p>The reading of the document happens once, in {@link ApiModelBuilder}, and
 * what is left here is the walk over its result. A subclass supplies
 * {@link #buildMethod} for its own language; nothing below this class reads
 * {@code io.swagger.*} except the type definers, which still resolve a schema to
 * a type.
 *
 * <p>Public although nothing outside this package references it: Kotlin refuses
 * to let a public class expose a package-private supertype, and
 * {@code KotlinAPIExtractor} has to be reachable from {@code KotlinCodegen} in
 * the root package.
 *
 * @param <T> the generated type
 * @param <B> the builder that produces it
 */
public abstract class APIExtractor<T, B> implements TypeSpecExtractor<T> {
    private final GeneratorParams params;
    private final ApiModelBuilder modelBuilder;
    private final BiFunction<String, Role, B> builderSupplier;
    private final Function<B, T> buildInvoker;

    /**
     * Creates an extractor for one target language.
     *
     * @param params          what to generate and how
     * @param modelBuilder    reads the document into the language-neutral model
     * @param builderSupplier creates the builder for one generated interface
     * @param buildInvoker    finishes a builder into a generated type
     */
    protected APIExtractor(GeneratorParams params,
                           ApiModelBuilder modelBuilder,
                           BiFunction<String, Role, B> builderSupplier,
                           Function<B, T> buildInvoker) {
        this.params = params;
        this.modelBuilder = modelBuilder;
        this.builderSupplier = builderSupplier;
        this.buildInvoker = buildInvoker;
    }

    /**
     * The target web framework.
     *
     * @return the configured framework
     */
    protected Framework getFramework() {
        return params.getFramework();
    }

    /**
     * Generates one interface per tag, for every requested role.
     *
     * @param openAPI            the document to read
     * @param typeSpecBiConsumer receives each generated interface
     */
    public final void extractTypeSpecs(OpenAPI openAPI, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        for (Role role : params.getGenerate()) {
            //the Api interface never carries response-related artifacts
            boolean responseParameter = role != Role.API && params.isGenerateResponseParameter();
            for (InterfaceModel generatedInterface : modelBuilder.build(openAPI, role)) {
                B classBuilder = builderSupplier.apply(generatedInterface.name(), role);
                for (OperationModel operation : generatedInterface.operations()) {
                    buildMethod(openAPI, classBuilder, operation, role, responseParameter);
                }
                typeSpecBiConsumer.accept(ClassCategory.CONTROLLER, buildInvoker.apply(classBuilder));
            }
        }
    }

    /**
     * Emits one method for one operation.
     *
     * @param openAPI                   the document, still needed to resolve a
     *                                  schema to a type
     * @param classBuilder              the interface being built
     * @param operation                 the operation to emit
     * @param role                      the kind of interface being generated
     * @param generateResponseParameter whether response-related artifacts were
     *                                  requested
     */
    abstract void buildMethod(OpenAPI openAPI,
                              B classBuilder,
                              OperationModel operation,
                              Role role,
                              boolean generateResponseParameter);
}
