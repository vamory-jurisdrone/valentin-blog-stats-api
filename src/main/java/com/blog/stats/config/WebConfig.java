package com.blog.stats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * navigator.sendBeacon envoie une chaîne JSON avec {@code Content-Type: text/plain;charset=UTF-8}.
 * On ajoute un convertisseur Jackson en lecture seule, limité aux DTO de l'API, pour lire ces corps.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new TextPlainJsonConverter(objectMapper));
    }

    static final class TextPlainJsonConverter extends AbstractJackson2HttpMessageConverter {

        private static final String DTO_PACKAGE = "com.blog.stats.dto";

        TextPlainJsonConverter(ObjectMapper objectMapper) {
            super(objectMapper, MediaType.TEXT_PLAIN);
        }

        @Override
        public boolean canRead(Type type, @Nullable Class<?> contextClass, @Nullable MediaType mediaType) {
            return type instanceof Class<?> clazz
                    && DTO_PACKAGE.equals(clazz.getPackageName())
                    && super.canRead(type, contextClass, mediaType);
        }

        /** Lecture seule : les réponses restent en application/json. */
        @Override
        public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(@Nullable Type type, Class<?> clazz, @Nullable MediaType mediaType) {
            return false;
        }
    }
}
