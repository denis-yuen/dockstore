/*
 * Copyright 2021 OICR and UCSC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dockstore.webservice.core;

import java.io.Serializable;

public class CategorySummary implements Serializable {
    private long id;
    private String name;
    private String description;
    private String displayName;
    private String topic;

    public CategorySummary(long id, String name, String description, String displayName, String topic) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.displayName = displayName;
        this.topic = topic;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTopic() {
        return (topic);
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof CategorySummary) {
            CategorySummary other = (CategorySummary) o;
            return (id == other.id);
        }
        return (false);
    }

    @Override
    public int hashCode() {
        return (int)id;
    }
}
