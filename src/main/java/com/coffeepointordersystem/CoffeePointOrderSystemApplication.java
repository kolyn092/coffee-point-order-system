package com.coffeepointordersystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.MutableCoercionConfig;
import tools.jackson.databind.type.LogicalType;

@SpringBootApplication
public class CoffeePointOrderSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoffeePointOrderSystemApplication.class, args);
	}

	@Bean
	public JsonMapperBuilderCustomizer configureJsonScalarCoercion() {
		return builder -> {
			builder.withCoercionConfig(
					LogicalType.Textual,
					config -> rejectCoercion(
							config,
							CoercionInputShape.Integer,
							CoercionInputShape.Float,
							CoercionInputShape.Boolean
					)
			);
			builder.withCoercionConfig(
					LogicalType.Integer,
					config -> rejectCoercion(
							config,
							CoercionInputShape.String,
							CoercionInputShape.Float,
							CoercionInputShape.Boolean
					)
			);
		};
	}

	private static void rejectCoercion(
			MutableCoercionConfig config,
			CoercionInputShape... inputShapes
	) {
		for (CoercionInputShape inputShape : inputShapes) {
			config.setCoercion(inputShape, CoercionAction.Fail);
		}
	}

}
