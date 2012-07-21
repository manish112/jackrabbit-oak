/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
 */
package org.apache.jackrabbit.oak.plugins.memory;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.spi.state.AbstractNodeState;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateDiff;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterators;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

public class ModifiedNodeState extends AbstractNodeState {

    private final NodeState base;

    private final Map<String, PropertyState> properties;

    private final Map<String, NodeState> nodes;

    public ModifiedNodeState(
            NodeState base,
            Map<String, PropertyState> properties,
            Map<String, NodeState> nodes) {
        this.base = base;
        this.properties = properties;
        this.nodes = nodes;
    }

    //---------------------------------------------------------< NodeState >--

    @Override
    public PropertyState getProperty(String name) {
        if (properties.containsKey(name)) {
            return properties.get(name);
        } else {
            return base.getProperty(name);
        }
    }

    @Override
    public long getPropertyCount() {
        long count = base.getPropertyCount();
        for (Map.Entry<String, PropertyState> entry : properties.entrySet()) {
            if (base.getProperty(entry.getKey()) != null) {
                if (entry.getValue() == null) {
                    count--;
                }
            } else {
                if (entry.getValue() != null) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Iterable<? extends PropertyState> getProperties() {
        if (properties.isEmpty()) {
            return base.getProperties(); // shortcut
        }
        final Iterable<? extends PropertyState> unmodified = base.getProperties();
        final Iterable<? extends PropertyState> modified = properties.values();

        return new Iterable<PropertyState>() {
            @Override
            public Iterator<PropertyState> iterator() {
                Iterator<? extends PropertyState> a = Iterators.filter(
                        unmodified.iterator(), new UnmodifiedPropertyPredicate());

                Iterator<? extends PropertyState> b = Iterators.filter(
                        modified.iterator(), Predicates.notNull());

                return Iterators.concat(a, b);
            }
        };
    }

    @Override
    public NodeState getChildNode(String name) {
        if (nodes.containsKey(name)) {
            return nodes.get(name);
        } else {
            return base.getChildNode(name);
        }
    }

    @Override
    public long getChildNodeCount() {
        long count = base.getChildNodeCount();
        for (Map.Entry<String, NodeState> entry : nodes.entrySet()) {
            if (base.getChildNode(entry.getKey()) != null) {
                if (entry.getValue() == null) {
                    count--;
                }
            } else {
                if (entry.getValue() != null) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Iterable<? extends ChildNodeEntry> getChildNodeEntries() {
        if (nodes.isEmpty()) {
            return base.getChildNodeEntries(); // shortcut
        }
        final Iterable<? extends ChildNodeEntry> unmodified = base.getChildNodeEntries();
        final Iterator<Entry<String, NodeState>> modified = nodes.entrySet().iterator();

        return new Iterable<ChildNodeEntry>() {
            @Override
            public Iterator<ChildNodeEntry> iterator() {
                Iterator<? extends ChildNodeEntry> a = Iterators.filter(
                        unmodified.iterator(), new UnmodifiedChildNodePredicate());

                Iterator<Entry<String, NodeState>> b = Iterators.filter(
                        modified, new UndeletedChildNodePredicate());

                return Iterators.concat(a, MemoryChildNodeEntry.iterator(b));
            }
        };
    }

    /**
     * Since we keep track of an explicit base node state for a
     * {@link ModifiedNodeState} instance, we can do this in two steps:
     * first compare the base states to each other (often a fast operation),
     * ignoring all changed properties and child nodes for which we have
     * further modifications, and then compare all the modified properties
     * and child nodes to those in the given base state.
     */
    @Override
    public void compareAgainstBaseState(
            NodeState base, final NodeStateDiff diff) {
        this.base.compareAgainstBaseState(base, new NodeStateDiff() {
            @Override
            public void propertyAdded(PropertyState after) {
                if (!properties.containsKey(after.getName())) {
                    diff.propertyAdded(after);
                }
            }
            @Override
            public void propertyChanged(
                    PropertyState before, PropertyState after) {
                if (!properties.containsKey(before.getName())) {
                    diff.propertyChanged(before, after);
                }
            }
            @Override
            public void propertyDeleted(PropertyState before) {
                if (!properties.containsKey(before.getName())) {
                    diff.propertyDeleted(before);
                }
            }
            @Override
            public void childNodeAdded(String name, NodeState after) {
                if (!nodes.containsKey(name)) {
                    diff.childNodeAdded(name, after);
                }
            }
            @Override
            public void childNodeChanged(String name, NodeState before, NodeState after) {
                if (!nodes.containsKey(name)) {
                    diff.childNodeChanged(name, before, after);
                }
            }
            @Override
            public void childNodeDeleted(String name, NodeState before) {
                if (!nodes.containsKey(name)) {
                    diff.childNodeDeleted(name, before);
                }
            }
        });

        for (Map.Entry<String, PropertyState> entry : properties.entrySet()) {
            PropertyState before = base.getProperty(entry.getKey());
            PropertyState after = entry.getValue();
            if (before == null && after == null) {
                // do nothing
            } else if (after == null) {
                diff.propertyDeleted(before);
            } else if (before == null) {
                diff.propertyAdded(after);
            } else if (!before.equals(after)) {
                diff.propertyChanged(before, after);
            }
        }

        for (Map.Entry<String, NodeState> entry : nodes.entrySet()) {
            String name = entry.getKey();
            NodeState before = base.getChildNode(name);
            NodeState after = entry.getValue();
            if (before == null && after == null) {
                // do nothing
            } else if (after == null) {
                diff.childNodeDeleted(name, before);
            } else if (before == null) {
                diff.childNodeAdded(name, after);
            } else if (!before.equals(after)) {
                diff.childNodeChanged(name, before, after);
            }
        }
    }

    //-----------------------------------------------------------< private >--

    private class UnmodifiedPropertyPredicate implements Predicate<PropertyState> {
        @Override
        public boolean apply(PropertyState property) {
            return !properties.containsKey(property.getName());
        }
    }

    private class UnmodifiedChildNodePredicate implements Predicate<ChildNodeEntry> {
        @Override
        public boolean apply(ChildNodeEntry entry) {
            return !nodes.containsKey(entry.getName());
        }
    }

    private static class UndeletedChildNodePredicate implements Predicate<Entry<?, ?>> {
        @Override
        public boolean apply(Entry<?, ?> entry) {
            return entry.getValue() != null;
        }
    }

}
