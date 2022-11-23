package ru.curs.hurdygurdy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import ru.curs.hurdygurdy.example.OneOf
import ru.curs.hurdygurdy.example.Variant1
import ru.curs.hurdygurdy.example.Variant2

class KOMTest {

    @Test
    fun testUserConfigOneOf() {
        val mapper = jacksonObjectMapper()
        val userConfigSingleDBJson = """{"id1": 1 }"""
        val userConfig: OneOf = mapper.readValue(userConfigSingleDBJson)
        Assertions.assertTrue(userConfig is Variant1)
        Assertions.assertFalse(userConfig is Variant2)
    }

}
