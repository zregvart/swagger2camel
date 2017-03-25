/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.zregvart.s2c;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.MethodSpec.Builder;
import com.squareup.javapoet.TypeSpec;

import io.swagger.models.HttpMethod;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.AbstractSerializableParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.properties.Property;
import io.swagger.parser.SwaggerParser;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.CollectionFormat;
import org.apache.camel.model.rest.RestDefinition;
import org.apache.camel.model.rest.RestParamType;
import org.apache.camel.util.ObjectHelper;

public class SwaggerToCamelMain {

    static class RestDslOperation {

        private final MethodSpec.Builder configure;

        private final String path;

        public RestDslOperation(final Builder configure, final String path) {
            this.configure = configure;
            this.path = path;
        }

        void appendMethodTo(final StringBuilder statement, final List<Object> args, final String method,
            final Boolean value) {
            if (value != null) {
                statement.append("\n.").append(method).append("($L)");
                args.add(value);
            }
        }

        void appendMethodTo(final StringBuilder statement, final List<Object> args, final String method,
            final Class<? extends Enum> enumType, final String value) {
            if (ObjectHelper.isNotEmpty(value)) {
                statement.append("\n.").append(method).append("($T.$L)");
                args.add(enumType);
                args.add(value);
            }
        }

        void appendMethodTo(final StringBuilder statement, final List<Object> args, final String method,
            final Object value) {
            if (value != null) {
                statement.append("\n.").append(method).append("($L)");
                args.add(String.valueOf(value));
            }
        }

        void appendMethodTo(final StringBuilder statement, final List<Object> args, final String method,
            final String value) {
            if (ObjectHelper.isNotEmpty(value)) {
                statement.append("\n.").append(method).append("($S)");
                args.add(value);
            }
        }

        void appendMethodWithJoinedValuesTo(final StringBuilder statement, final List<Object> args, final String method,
            final List<String> values) {
            if (values == null || values.isEmpty()) {
                return;
            }

            appendMethodTo(statement, args, method, values.stream().collect(Collectors.joining(",")));
        }

        void appendMethodWithVarargValuesTo(final StringBuilder statement, final List<Object> args, final String method,
            final List<?> values) {
            if (values == null || values.isEmpty()) {
                return;
            }

            statement.append("\n.").append(method).append("(");
            values.stream().map(v -> "$S")
                .collect(Collector.of(() -> statement, StringBuilder::append, (l, r) -> l.append(",").append(r)));

            values.forEach(v -> args.add(String.valueOf(v)));
        }

        void appendParamTo(final StringBuilder statement, final List<Object> args, final Parameter parameter) {
            statement.append("\n.param()");
            appendMethodTo(statement, args, "name", parameter.getName());
            appendMethodTo(statement, args, "type", RestParamType.class, parameter.getIn());
            if (parameter instanceof AbstractSerializableParameter) {
                final AbstractSerializableParameter serializableParameter = (AbstractSerializableParameter) parameter;

                final String dataType = serializableParameter.getType();
                appendMethodTo(statement, args, "dataType", dataType);

                appendMethodWithVarargValuesTo(statement, args, "allowableValues",
                    serializableParameter.getEnumValue());
                appendMethodTo(statement, args, "collectionFormat", CollectionFormat.class,
                    serializableParameter.getCollectionFormat());
                appendMethodTo(statement, args, "defaultValue", serializableParameter.getDefault());

                final Property items = serializableParameter.getItems();
                if ("array".equals(dataType) && items != null) {
                    appendMethodTo(statement, args, "arrayType", items.getType());
                }
            }
            appendMethodTo(statement, args, "required", parameter.getRequired());
            appendMethodTo(statement, args, "description", parameter.getDescription());
            statement.append("\n.endParam()");
        }

        String processorNameFor(final Operation operation) {
            return operation.getOperationId();
        }

        void visit(final HttpMethod method, final Operation operation) {
            final List<Object> args = new ArrayList<>();

            final StringBuilder statement = new StringBuilder("rest.$L($S)");
            final String methodName = method.name().toLowerCase();
            args.add(methodName);
            args.add(path);

            appendMethodTo(statement, args, "id", operation.getOperationId());
            appendMethodTo(statement, args, "description", operation.getDescription());
            appendMethodWithJoinedValuesTo(statement, args, "consumes", operation.getConsumes());
            appendMethodWithJoinedValuesTo(statement, args, "produces", operation.getProduces());

            operation.getParameters().forEach(parameter -> {
                appendParamTo(statement, args, parameter);
            });

            statement.append(".route().process($S)");
            args.add(processorNameFor(operation));

            configure.addStatement(statement.toString(), args.toArray(new Object[args.size()]));
        }
    }

    static class RestDslStatement {

        private final Builder configure;

        public RestDslStatement(final MethodSpec.Builder configure) {
            this.configure = configure;
        }

        void visit(final String path, final Path definition) {
            final RestDslOperation restDslOperation = new RestDslOperation(configure, path);
            definition.getOperationMap().forEach(restDslOperation::visit);
        }

    }

    public static void main(final String[] args) throws IOException {
        final Swagger swagger = new SwaggerParser().read("petstore.json");

        final MethodSpec configure = buildRestDsl(swagger);

        final TypeSpec generatedRouteBulder = TypeSpec.classBuilder(className(swagger)).superclass(RouteBuilder.class)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL).addMethod(configure).build();

        final JavaFile javaFile = JavaFile.builder("com.example.helloworld", generatedRouteBulder).build();

        javaFile.writeTo(new File("target/generated-sources/swagger-routes"));
    }

    static MethodSpec buildRestDsl(final Swagger swagger) {
        final MethodSpec.Builder configure = MethodSpec.methodBuilder("configure").addModifiers(Modifier.PUBLIC)
            .returns(void.class);
        configure.addStatement("final $T rest = rest()", RestDefinition.class);

        final RestDslStatement restDslStatement = new RestDslStatement(configure);
        swagger.getPaths().forEach(restDslStatement::visit);

        return configure.build();
    }

    static String className(final Swagger swagger) {
        return swagger.getInfo().getTitle().chars().filter(Character::isJavaIdentifierPart).boxed().collect(Collector
            .of(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append, StringBuilder::toString));
    }
}
