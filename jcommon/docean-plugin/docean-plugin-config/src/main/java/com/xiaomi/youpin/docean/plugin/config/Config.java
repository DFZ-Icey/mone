/*
 *  Copyright 2020 Xiaomi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.xiaomi.youpin.docean.plugin.config;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * @author goodjava@qq.com
 */
@Slf4j
public class Config {

    private Properties properties;

    public Config() {
        InputStream is = Config.class.getClassLoader().getResourceAsStream("config.properties");
        properties = new Properties();
        if (null != is) {
            try {
                properties.load(is);
            } catch (Exception e) {
                log.warn("load config error:{}", e.getMessage());
            }
        }
    }

    public void put(String key, String value) {
        this.properties.put(key, value);
    }


    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue).toString().trim();
    }

    public Map<String, String> getByPrefix(String prefix, boolean trimPrefix) {
        Map<String, String> result = new HashMap<>();
        Enumeration<String> en = (Enumeration<String>) properties.propertyNames();
        while (en.hasMoreElements()) {
            String propName = en.nextElement();
            String propValue = properties.getProperty(propName);

            if (propName.startsWith(prefix)) {
                if (trimPrefix) {
                    propName = propName.substring(prefix.length());
                }
                result.put(propName, propValue);
            }
        }

        return result;
    }

    public void forEach(BiConsumer<Object, Object> consumer) {
        this.properties.forEach(consumer);
    }

}
