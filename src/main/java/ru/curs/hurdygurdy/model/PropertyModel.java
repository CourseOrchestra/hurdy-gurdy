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

package ru.curs.hurdygurdy.model;

/**
 * One property of a generated type, wherever in an {@code allOf} chain it was
 * declared.
 *
 * <p>Every back end asks the same five things of a property — what to call it,
 * whether the wire name has to be pinned, what type it has, whether it must be
 * there, whether it may be null — and each used to answer them for itself. They
 * are answered once, here.
 *
 * @param specKey          the property name as written in the specification,
 *                         which is also its name on the wire
 * @param identifier       the generated property name
 * @param jsonNameOverride the wire name to pin with an explicit
 *                         {@code @JsonProperty}, or null when the configured
 *                         Jackson naming already reproduces {@code specKey} from
 *                         {@code identifier}
 * @param type             the property's type
 * @param required         whether the declaring object lists it as required
 * @param nullable         whether the value may be an explicit null, decided
 *                         through any {@code $ref} by
 *                         {@link TypeModelBuilder#isNullableType}
 * @param defaultValue     the default the property declares, never null — see
 *                         {@link DefaultValue#NONE}
 */
public record PropertyModel(String specKey, String identifier, String jsonNameOverride,
                            TypeRef type, boolean required, boolean nullable,
                            DefaultValue defaultValue) {

    /**
     * A property's default, already classified by what a generated initializer
     * would have to be.
     *
     * <p>Classifying is the specification's business and spelling is the
     * language's: {@code %S} against {@code "..."}, a constant of a generated
     * enum against a constructor call. A back end that has no place to put a
     * default — the Java DTO styles initialize nothing — ignores this entirely.
     *
     * @param style what kind of initializer the default calls for
     * @param text  the default as written in the document, null for
     *              {@link Style#NONE}
     */
    public record DefaultValue(Style style, String text) {

        /** The property declares no default. */
        public static final DefaultValue NONE = new DefaultValue(Style.NONE, null);

        /** What kind of initializer a default calls for. */
        public enum Style {
            /** No default at all. */
            NONE,
            /** An array's default: an empty list, whatever the document listed. */
            EMPTY_LIST,
            /** A string literal. */
            STRING,
            /** A constant of the referenced enum. */
            ENUM_CONSTANT,
            /** An empty object: a no-argument construction of the referenced type. */
            EMPTY_OBJECT,
            /**
             * A structured default on a {@code $ref} — {@code {order: SIMILARITY,
             * limit: 10}} and the like. No target language can spell it as an
             * initializer expression, so it is dropped rather than mis-rendered.
             */
            STRUCTURED,
            /** Anything else: a number or a boolean, written out as it stands. */
            LITERAL
        }
    }
}
