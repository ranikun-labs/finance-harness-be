package labs.ranikun.finance.common.config

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.type.LogicalType

@Configuration(proxyBeanMethods = false)
class JacksonConfiguration {

    @Bean
    fun strictJsonMapperCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            builder.withCoercionConfig(LogicalType.Textual) { coercions ->
                listOf(
                    CoercionInputShape.Integer,
                    CoercionInputShape.Float,
                    CoercionInputShape.Boolean,
                    CoercionInputShape.Binary,
                    CoercionInputShape.Array,
                    CoercionInputShape.Object,
                ).forEach { shape ->
                    coercions.setCoercion(shape, CoercionAction.Fail)
                }
            }
        }
}
