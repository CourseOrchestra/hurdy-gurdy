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

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.util.List;

/**
 * The annotation vocabulary of one target web framework, for Java output.
 *
 * <p>Turning an operation into an interface method is one algorithm — annotate
 * the method, set the return type, add the body, then the path, query and header
 * parameters, then whatever context parameters the framework wants. Only the
 * <em>names</em> in it change between Spring and Quarkus. That algorithm used to
 * be written out once per framework and once more for the Spring client, three
 * copies in this language and three more in Kotlin; a binding is what is left
 * when the algorithm is factored out of them, and
 * {@link JavaAPIExtractor#buildMethod} is the algorithm itself.
 *
 * <p>Note that the Spring client is a binding of its own
 * ({@link JavaSpringClientBinding}) rather than a special case of a role: it
 * speaks a different annotation dialect ({@code @GetExchange} and friends) and
 * wraps its return type differently. Roles that share a dialect share a binding,
 * and {@link Role} is passed only where a framework genuinely distinguishes them.
 *
 * <p>Until the model of step 3 exists there has to be one of these per language,
 * because every method here returns a JavaPoet object. The Kotlin counterpart is
 * {@code KotlinFrameworkBinding}, and the two are expected to stay in step; see
 * {@code ApiParityTest}.
 */
interface JavaFrameworkBinding {

    /**
     * The annotations that map the method to an HTTP endpoint, in the order they
     * should appear.
     *
     * <p>An empty list means the framework has no mapping for this verb, which
     * {@link JavaAPIExtractor#buildMethod} reports as an error rather than
     * emitting an unmapped method.
     *
     * @param httpMethod the HTTP verb
     * @param path       the path the operation is declared under
     * @param operation  the operation, for its media types
     * @return the method-level annotations, empty when the verb is unsupported
     */
    List<AnnotationSpec> methodAnnotations(PathItem.HttpMethod httpMethod, String path, Operation operation);

    /**
     * The annotations binding a path parameter. A path variable is part of the
     * URL and so never carries a default.
     *
     * @param parameter the parameter as declared
     * @return its annotations
     */
    List<AnnotationSpec> pathParamAnnotations(Parameter parameter);

    /**
     * The annotations binding a query parameter.
     *
     * @param parameter    the parameter as declared
     * @param defaultValue its effective default, or null when it has none
     * @return its annotations
     */
    List<AnnotationSpec> queryParamAnnotations(Parameter parameter, String defaultValue);

    /**
     * The annotations binding a header parameter.
     *
     * @param parameter    the parameter as declared
     * @param defaultValue its effective default, or null when it has none
     * @return its annotations
     */
    List<AnnotationSpec> headerParamAnnotations(Parameter parameter, String defaultValue);

    /**
     * The annotation marking the single-part request body, or null when the
     * framework infers it from the signature (as JAX-RS does).
     *
     * @return the body annotation, or null
     */
    AnnotationSpec bodyAnnotation();

    /**
     * The annotation binding one part of a multipart request body.
     *
     * @param partName the part's name on the wire
     * @return its annotation
     */
    AnnotationSpec multipartPartAnnotation(String partName);

    /**
     * The type of a binary multipart part: an uploaded file.
     *
     * @return the upload type
     */
    ClassName multipartPartType();

    /**
     * The type of a binary single-part request or response body: a
     * converter-backed stream.
     *
     * @return the binary body type
     */
    ClassName binaryBodyType();

    /**
     * Sets the method's return type, wrapping it as the framework requires when
     * response artifacts were asked for.
     *
     * @param method                   the method being built
     * @param dtoReturn                the type derived from the 2xx response
     * @param generateResponseParameter whether response-related artifacts were requested
     */
    void applyReturn(MethodSpec.Builder method, TypeName dtoReturn, boolean generateResponseParameter);

    /**
     * Adds the framework's context parameters — the raw request and response
     * handles a server-side method may want.
     *
     * @param method                   the method being built
     * @param operation                the operation, for {@code x-include-request}
     * @param role                     the interface being generated
     * @param generateResponseParameter whether response-related artifacts were requested
     */
    void addContextParameters(MethodSpec.Builder method, Operation operation, Role role,
                              boolean generateResponseParameter);
}
