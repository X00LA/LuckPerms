/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.caching.MetaData;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.NodeFactory;
import me.lucko.luckperms.common.verbose.CheckOrigin;
import me.lucko.luckperms.sponge.LPSpongePlugin;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import me.lucko.luckperms.sponge.service.LuckPermsSubjectData;
import me.lucko.luckperms.sponge.service.ProxyFactory;
import me.lucko.luckperms.sponge.service.model.LPSubject;
import me.lucko.luckperms.sponge.service.model.LPSubjectCollection;
import me.lucko.luckperms.sponge.service.reference.LPSubjectReference;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.permission.Subject;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public class SpongeUser extends User {

    private final UserSubject spongeData;

    public SpongeUser(UUID uuid, LPSpongePlugin plugin) {
        super(uuid, plugin);
        this.spongeData = new UserSubject(plugin, this);
    }

    public SpongeUser(UUID uuid, String name, LPSpongePlugin plugin) {
        super(uuid, name, plugin);
        this.spongeData = new UserSubject(plugin, this);
    }

    public UserSubject sponge() {
        return this.spongeData;
    }

    public static final class UserSubject implements LPSubject {
        private final SpongeUser parent;
        private final LPSpongePlugin plugin;

        private final LuckPermsSubjectData subjectData;

        private final LuckPermsSubjectData transientSubjectData;

        private UserSubject(LPSpongePlugin plugin, SpongeUser parent) {
            this.parent = parent;
            this.plugin = plugin;
            this.subjectData = new LuckPermsSubjectData(true, plugin.getService(), parent, this);
            this.transientSubjectData = new LuckPermsSubjectData(false, plugin.getService(), parent, this);
        }

        @Override
        public String getIdentifier() {
            return this.plugin.getUuidCache().getExternalUUID(this.parent.getUuid()).toString();
        }

        @Override
        public Optional<String> getFriendlyIdentifier() {
            return this.parent.getName();
        }

        @Override
        public Optional<CommandSource> getCommandSource() {
            final UUID uuid = this.plugin.getUuidCache().getExternalUUID(this.parent.getUuid());
            return Sponge.getServer().getPlayer(uuid).map(Function.identity());
        }

        @Override
        public LPSubjectCollection getParentCollection() {
            return this.plugin.getService().getUserSubjects();
        }

        @Override
        public Subject sponge() {
            return ProxyFactory.toSponge(this);
        }

        @Override
        public LuckPermsService getService() {
            return this.plugin.getService();
        }

        @Override
        public LuckPermsSubjectData getSubjectData() {
            return this.subjectData;
        }

        @Override
        public LuckPermsSubjectData getTransientSubjectData() {
            return this.transientSubjectData;
        }

        @Override
        public Tristate getPermissionValue(ImmutableContextSet contexts, String permission) {
            return this.parent.getCachedData().getPermissionData(this.plugin.getContextManager().formContexts(contexts)).getPermissionValue(permission, CheckOrigin.PLATFORM_LOOKUP_CHECK);
        }

        @Override
        public boolean isChildOf(ImmutableContextSet contexts, LPSubjectReference parent) {
            return parent.getCollectionIdentifier().equals(PermissionService.SUBJECTS_GROUP) && getPermissionValue(contexts, NodeFactory.groupNode(parent.getSubjectIdentifier())).asBoolean();
        }

        @Override
        public ImmutableList<LPSubjectReference> getParents(ImmutableContextSet contexts) {
            ImmutableSet.Builder<LPSubjectReference> subjects = ImmutableSet.builder();

            for (Map.Entry<String, Boolean> entry : this.parent.getCachedData().getPermissionData(this.plugin.getContextManager().formContexts(contexts)).getImmutableBacking().entrySet()) {
                if (!entry.getValue()) {
                    continue;
                }

                String groupName = NodeFactory.parseGroupNode(entry.getKey());
                if (groupName == null) {
                    continue;
                }

                if (this.plugin.getGroupManager().isLoaded(groupName)) {
                    subjects.add(this.plugin.getService().getGroupSubjects().loadSubject(groupName).join().toReference());
                }
            }

            subjects.addAll(this.plugin.getService().getUserSubjects().getDefaults().getParents(contexts));
            subjects.addAll(this.plugin.getService().getDefaults().getParents(contexts));

            return getService().sortSubjects(subjects.build());
        }

        @Override
        public Optional<String> getOption(ImmutableContextSet contexts, String s) {
            MetaData data = this.parent.getCachedData().getMetaData(this.plugin.getContextManager().formContexts(contexts));
            if (s.equalsIgnoreCase(NodeFactory.PREFIX_KEY)) {
                if (data.getPrefix() != null) {
                    return Optional.of(data.getPrefix());
                }
            }

            if (s.equalsIgnoreCase(NodeFactory.SUFFIX_KEY)) {
                if (data.getSuffix() != null) {
                    return Optional.of(data.getSuffix());
                }
            }

            String val = data.getMeta().get(s);
            if (val != null) {
                return Optional.of(val);
            }

            Optional<String> v = this.plugin.getService().getUserSubjects().getDefaults().getOption(contexts, s);
            if (v.isPresent()) {
                return v;
            }

            return this.plugin.getService().getDefaults().getOption(contexts, s);
        }

        @Override
        public void invalidateCaches(CacheLevel cacheLevel) {
            // invalidate for all changes
            this.parent.getCachedData().invalidateCaches();
        }
    }

}
