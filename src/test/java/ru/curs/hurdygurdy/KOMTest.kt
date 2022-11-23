package ru.curs.hurdygurdy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import ru.curs.hurdygurdy.example.UserConfig

class KOMTest {

    @Test
    fun testUserConfigOneOf() {
        val mapper = jacksonObjectMapper()
        val userConfigSingleDBJson = """{ "user_config_single_db":{"id": 1 }}"""
        val userConfig: UserConfig = mapper.readValue(userConfigSingleDBJson)

        Assertions.assertNotNull(userConfig.userConfigSingleDB)
        Assertions.assertNull(userConfig.userConfigMultipleDB)
    }

}
