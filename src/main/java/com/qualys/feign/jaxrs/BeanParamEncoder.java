/*
 * Licensed to Qualys, Inc. (QUALYS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * QUALYS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.qualys.feign.jaxrs;

import feign.RequestTemplate;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.template.*;

import java.lang.reflect.Type;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by sskrla on 10/12/15.
 */
class BeanParamEncoder implements Encoder {
    final Encoder delegate;

    public BeanParamEncoder() {
        this.delegate = new Encoder.Default();
    }

    public BeanParamEncoder(Encoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public void encode(Object object, Type bodyType, RequestTemplate template) throws EncodeException {
        if (template.methodMetadata().indexToExpander() == null)
            template.methodMetadata().indexToExpander(new HashMap<>());

        if (object instanceof Object[] objects) {
            for (Object internalObject : objects)
                if (internalObject instanceof EncoderContext ctx)
                    resolve(template, ctx);
        } else {
            this.delegate.encode(object, bodyType, template);
        }
    }

    private static final Pattern ESCAPED_CURLY_BRACES = Pattern.compile("%7B(\\w+)%7D");

    private void resolve(RequestTemplate mutable, EncoderContext ctx) {
        Map<String, Object> variables = ctx.values;
        JaxrsUriTemplate uriTemplate = JaxrsUriTemplate.create(removeEmptyQueryParameters(mutable.url(), ctx), !mutable.decodeSlash(),
                mutable.requestCharset());

        /// escape opening curly brace before expand
        variables.forEach((key, value) -> {
            if (value instanceof String valueString) {
                String escapedValue = valueString.replace("{", "%7B");
                variables.put(key, escapedValue);
            }
        });

        String expanded = uriTemplate.expand(variables);

        /// unescape opening curly brace and params after expand
        if (expanded != null) {
            expanded = ESCAPED_CURLY_BRACES.matcher(expanded).replaceAll("{$1}").replace("%257B", "%7B");
        }

        mutable.uri(expanded);

        /// expand headers
        Map<String, Collection<String>> headers = mutable.headers();
        mutable.headers(Collections.emptyMap());
        for (Map.Entry<String, Collection<String>> header : headers.entrySet()) {
            HeaderTemplate headerTemplate = HeaderTemplate.create(header.getKey(), header.getValue());
            String expandedHeader = headerTemplate.expand(variables);
            if (!expandedHeader.isEmpty())
                mutable.header(headerTemplate.getName(), expandedHeader);
            else
                if (!isFromBeanParam(headerTemplate.getName(), ctx))
                    mutable.header(headerTemplate.getName(), headerTemplate.getValues());
        }
    }

    private static boolean isFromBeanParam(String paramName, EncoderContext ctx) {
        for (String[] names : ctx.transformer.names)
            for (String name : names)
                if (name.equals(paramName))
                    return true;

        return false;
    }

    public static String removeEmptyQueryParameters(String template, EncoderContext ctx) {
        Map<String, Object> variables = ctx.values;

        // Регулярное выражение для поиска шаблонных переменных
        Pattern pattern = Pattern.compile("\\{(\\w+)}");
        Matcher matcher = pattern.matcher(template);

        // В цикле перебираем все переменные в шаблоне
        while (matcher.find()) {
            String key = matcher.group(1); // Получаем имя переменной
            Object value = variables.get(key); // Получаем значение переменной из Map

            if (isFromBeanParam(key, ctx) && (value == null || String.valueOf(value).isEmpty())) {
                // Если значение переменной пусто или не задано, удаляем из шаблона
                template = template.replaceAll("[&]?" + key + "=\\{" + key + "}", "");
            }
        }

        template = template.replace("?&", "?"); // Исправляем случай "?&" после удаления первого параметра
        template = template.replaceAll("\\?$", ""); // Удаляем висячий "?" в конце, если все параметры были удалены

        return template;
    }
}
