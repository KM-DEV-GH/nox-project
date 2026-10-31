package com.nox.integration.support;

import java.io.IOException;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Keeps component scanning to production classes when an application is started manually from a
 * test (outside the Spring test context, which normally provides this filtering).
 */
public class TestSourcesExcludeFilter extends TypeExcludeFilter {

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        return metadataReader.getResource().getURL().toString().contains("/test-classes/");
    }
}
