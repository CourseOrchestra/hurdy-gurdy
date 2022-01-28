package ru.curs.hurdygurdy;

import java.util.List;
import java.util.function.BiConsumer;

public interface TypeProducersFactory<T> {
    TypeDefiner<T> createTypeDefiner(BiConsumer<ClassCategory, T> typeSpecBiConsumer);

    List<TypeSpecExtractor<T>> typeSpecExtractors(TypeDefiner<T> typeDefiner);
}
