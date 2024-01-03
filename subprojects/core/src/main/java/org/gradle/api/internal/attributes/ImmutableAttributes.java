/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.attributes;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;

import java.util.Map;
import java.util.TreeMap;

public interface ImmutableAttributes extends AttributeContainerInternal {
    ImmutableAttributes EMPTY = new DefaultImmutableAttributes();

    /**
     * Locates the entry for the given attribute. Returns a 'missing' value when not present.
     *
     * <strong>WARNING: Attribute type information is often unreliable!</strong>  Attributes of consumers will not
     * have their type information available.  It will show as {@code String} instead of {@code <T>} and
     * this method will <strong>NOT</strong> be useful to locate an entry for a consumer attribute when
     * searching by the constants in the particular attribute interface.
     *
     * You should prefer {@link #findEntry(String)} and search by name to avoid these sorts of issues.
     */
    <T> AttributeValue<T> findEntry(Attribute<T> key);

    /**
     * Locates the entry for the attribute with the given name. Returns a 'missing' value when not present.
     */
    AttributeValue<?> findEntry(String key);

    @Override
    ImmutableSet<Attribute<?>> keySet();

    /**
     * Returns a single combined map of all attribute values in the given sources, keyed by attribute name.
     *
     * @param sources The sources to combine
     * @return The combined map of attribute values
     */
    static Map<String, Attribute<?>> mapOfAll(AttributeContainer... sources) {
        Map<String, Attribute<?>> allAttributes = new TreeMap<>();
        for (AttributeContainer source : sources) {
            for (Attribute<?> attribute : source.keySet()) {
                allAttributes.put(attribute.getName(), attribute);
            }
        }
        return allAttributes;
    }
}
