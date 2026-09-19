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

import java.util.ArrayList;
import java.util.List;

/**
 * The type of a value at one point of use, in no particular language.
 *
 * <p>This is the answer to "what does this schema mean as a type?", decided once
 * in {@link TypeModelBuilder} and spelled twice — {@code byte[]} against
 * {@code ByteArray}, a boxed {@code Integer} against a nullable {@code Int}. The
 * decision tree used to be written out per language, which is how the two back
 * ends came to disagree about an untitled inline object.
 *
 * <p>A {@code format} does not survive as a field: {@code date},
 * {@code date-time}, {@code uuid} and {@code binary} are the only ones the
 * generator acts on, and each is its own {@link Kind}, so a mapping table is an
 * exhaustive switch rather than a switch with a nested string test.
 *
 * @param kind         what sort of type this is
 * @param nullable     what the document says about null <em>here</em>:
 *                     {@code TRUE} when it admits null, {@code FALSE} when it
 *                     explicitly forbids it, {@code null} when it says nothing
 *                     and the caller decides. For a {@link Kind#REFERENCE} to a
 *                     named component it is what that component declares
 * @param nullUnion    whether the site spelled the type as the nullable wrapper
 *                     {@code anyOf: [X, null]}, which states nullability
 *                     <em>outside</em> the schema {@code nullable} was read from
 * @param element      the element type of a {@link Kind#ARRAY}, null otherwise
 * @param declaredType the type declared at this very site and therefore still to
 *                     be generated — an inline enum for {@link Kind#ENUM}, an
 *                     inline titled object for {@link Kind#REFERENCE} — or null
 *                     when the type is merely named here
 * @param packageName  the package of a {@link Kind#REFERENCE}, empty for a
 *                     nested inline enum, null for everything else
 * @param simpleName   the simple name of a {@link Kind#REFERENCE} or
 *                     {@link Kind#ENUM}, null for everything else
 */
public record TypeRef(Kind kind, Boolean nullable, boolean nullUnion, TypeRef element,
                      TypeModel declaredType, String packageName, String simpleName) {

    /**
     * The same reference with its nullability settled by the caller.
     *
     * <p>An array element is the case that needs it: whether a list may hold
     * nulls is decided by the element schema through
     * {@link TypeModelBuilder#isNullableType}, not by the three-valued statement
     * at the site, so the answer is baked in here rather than re-derived by each
     * back end.
     *
     * @param value whether null is permitted
     * @return a copy stating exactly that
     */
    public TypeRef withNullable(boolean value) {
        return new TypeRef(kind, value, false, element, declaredType, packageName, simpleName);
    }

    /**
     * Every type declared at this site or inside it, outermost first.
     *
     * <p>Resolving a type used to <em>emit</em> the types it met on the way — a
     * nested enum onto a builder the caller had to thread down the recursion, an
     * inline object straight into the global sink. They are values here instead,
     * and a back end decides where each one goes and what modifiers it needs.
     *
     * @return the inline enums and objects this reference brings with it
     */
    public List<TypeRef> declarations() {
        List<TypeRef> result = new ArrayList<>();
        collectDeclarations(result);
        return result;
    }

    private void collectDeclarations(List<TypeRef> result) {
        if (declaredType != null) {
            result.add(this);
        }
        if (element != null) {
            element.collectDeclarations(result);
        }
    }

    /** What sort of type a {@link TypeRef} is. */
    public enum Kind {
        /** A plain string. */
        STRING,
        /** A boolean. */
        BOOLEAN,
        /** An integer no wider than 32 bits. */
        INTEGER,
        /** An {@code int64} integer. */
        LONG,
        /** A {@code float} number. */
        FLOAT,
        /** A number of any other precision. */
        DOUBLE,
        /** A {@code date}-formatted string: a local date. */
        DATE,
        /** A {@code date-time}-formatted string: a zoned date and time. */
        DATE_TIME,
        /** A {@code uuid}-formatted string. */
        UUID,
        /** A {@code binary}-formatted string: bytes, base64-encoded on the wire. */
        BINARY,
        /** A list of {@link #element}. */
        ARRAY,
        /** An enumeration declared inline, to be generated inside the enclosing type. */
        ENUM,
        /** A generated class, named here and possibly also declared here. */
        REFERENCE,
        /** Any value at all: the schema constrains nothing a type could express. */
        ANY
    }
}
