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

package ru.curs.hurdygurdy.emit;

import ru.curs.hurdygurdy.ClassCategory;
import ru.curs.hurdygurdy.GeneratorParams;
import ru.curs.hurdygurdy.model.ArrayAliasType;
import ru.curs.hurdygurdy.model.EnumType;
import ru.curs.hurdygurdy.model.TypeModel;
import ru.curs.hurdygurdy.model.TypeModelBuilder;
import ru.curs.hurdygurdy.model.TypeRef;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Turns a described type into the generated type for one target language.
 *
 * <p>What a schema <em>means</em> is decided in
 * {@link ru.curs.hurdygurdy.model.TypeModelBuilder}; what is left here is how
 * the answer is spelled. A subclass is a mapping table and a set of builders,
 * and the two of them map onto genuinely different type systems — {@code byte[]}
 * against {@code ByteArray}, a record against a data class — which is why there
 * are two.
 *
 * <p>Deliberately free of any code-generation library: a subclass produces
 * {@code T} and nothing here knows what {@code T} is.
 *
 * @param <T> the generated type: a JavaPoet or KotlinPoet {@code TypeSpec}
 */
public abstract class TypeDefiner<T> {

    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final GeneratorParams params;
    private final TypeModelBuilder models;

    /**
     * Creates a type definer.
     *
     * @param params             what to generate and how
     * @param typeSpecBiConsumer receives types generated as a side effect of
     *                           resolving another, such as an inline object
     */
    public TypeDefiner(GeneratorParams params, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.params = params;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
        this.models = new TypeModelBuilder(params);
    }

    /**
     * The reader this definer emits from, shared with everything else that needs
     * to ask the specification a question during the same run — the API model
     * builder and both extractors — so that a linked document is parsed once and
     * an alias cycle is detected wherever it is met.
     *
     * @return the model builder
     */
    public final TypeModelBuilder models() {
        return models;
    }

    /**
     * The type generated for a component schema.
     *
     * @param name    the component's name
     * @param schema  the component's schema
     * @param openAPI the document it was declared in
     * @return the generated type
     */
    public final T getDTO(String name, Schema<?> schema, OpenAPI openAPI) {
        return emit(models.dto(name, schema, openAPI));
    }

    /**
     * The generated form of one described type, whichever of the four shapes it
     * has.
     */
    final T emit(TypeModel model) {
        if (model instanceof EnumType enumType) {
            return getEnum(enumType);
        }
        if (model instanceof ArrayAliasType alias) {
            return getArrayAlias(alias);
        }
        return getDTOClass(model);
    }

    /**
     * Generates every type a resolved reference declares along the way, handing
     * each one to whichever sink it belongs in.
     *
     * <p>This is the half that used to happen <em>inside</em> type resolution: a
     * nested enum was attached to a {@code TypeSpec.Builder} that every caller
     * had to thread through the recursion, and an inline titled object went
     * straight into the global sink. Resolving is now a pure function of the
     * schema, and where a declaration goes — nested in the type being built, or
     * beside it as a file of its own — is decided once, here, by the code that is
     * doing the building and therefore knows.
     *
     * @param ref    the resolved reference to look through
     * @param nested receives each enum declared inline, to be generated inside
     *               the type being built
     */
    final void emitDeclarations(TypeRef ref, Consumer<EnumType> nested) {
        for (TypeRef declaration : ref.declarations()) {
            if (declaration.kind() == TypeRef.Kind.ENUM) {
                nested.accept((EnumType) declaration.declaredType());
            } else {
                typeSpecBiConsumer.accept(ClassCategory.DTO, emit(declaration.declaredType()));
            }
        }
    }

    abstract T getEnum(EnumType type);

    /**
     * The model generated for an array alias when
     * {@link GeneratorParams#isGenerateAliasAsModel()} is set: a class extending
     * {@code ArrayList<Item>} (mirroring openapi-generator's
     * {@code generateAliasAsModel} output), so it serializes as a plain JSON
     * array.
     */
    abstract T getArrayAlias(ArrayAliasType type);

    /**
     * The class, record or interface generated for an {@link
     * ru.curs.hurdygurdy.model.ObjectType} or a {@link
     * ru.curs.hurdygurdy.model.PolymorphicType}.
     */
    abstract T getDTOClass(TypeModel model);

    /**
     * Prepares the definer for one generation run, discarding what the previous
     * one cached.
     *
     * @param currentSourceFile the specification being generated
     * @param listener          receives warnings raised while reading linked
     *                          documents
     */
    public void init(Path currentSourceFile, Consumer<String> listener) {
        models.init(currentSourceFile, listener);
    }
}
