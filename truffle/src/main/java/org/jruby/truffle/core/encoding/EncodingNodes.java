/*
 * Copyright (c) 2014, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 *
 * Contains code modified from JRuby's RubyConverter.java
 */
package org.jruby.truffle.core.encoding;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import org.jcodings.Encoding;
import org.jcodings.EncodingDB;
import org.jcodings.EncodingDB.Entry;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.util.CaseInsensitiveBytesHash.CaseInsensitiveBytesHashEntry;
import org.jcodings.util.Hash.HashEntry;
import org.jruby.runtime.Visibility;
import org.jruby.truffle.Layouts;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.builtins.CoreClass;
import org.jruby.truffle.builtins.CoreMethod;
import org.jruby.truffle.builtins.CoreMethodArrayArgumentsNode;
import org.jruby.truffle.builtins.NonStandard;
import org.jruby.truffle.builtins.Primitive;
import org.jruby.truffle.builtins.PrimitiveArrayArgumentsNode;
import org.jruby.truffle.builtins.UnaryCoreMethodNode;
import org.jruby.truffle.builtins.YieldingCoreMethodNode;
import org.jruby.truffle.core.cast.ToEncodingNode;
import org.jruby.truffle.core.cast.ToStrNode;
import org.jruby.truffle.core.cast.ToStrNodeGen;
import org.jruby.truffle.core.rope.CodeRange;
import org.jruby.truffle.core.rope.Rope;
import org.jruby.truffle.core.rope.RopeOperations;
import org.jruby.truffle.core.string.StringOperations;
import org.jruby.truffle.language.RubyGuards;
import org.jruby.truffle.language.RubyNode;
import org.jruby.truffle.language.SnippetNode;
import org.jruby.truffle.language.control.RaiseException;
import org.jruby.util.ByteList;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

@CoreClass("Encoding")
public abstract class EncodingNodes {

    @CoreMethod(names = "ascii_compatible?")
    public abstract static class AsciiCompatibleNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "encoding == cachedEncoding", limit = "getCacheLimit()")
        public boolean isAsciiCompatibleCached(DynamicObject encoding,
                                          @Cached("encoding") DynamicObject cachedEncoding,
                                          @Cached("isAsciiCompatible(cachedEncoding)") boolean isAsciiCompatible) {
            return isAsciiCompatible;
        }

        @Specialization(contains = "isAsciiCompatibleCached")
        public boolean isAsciiCompatibleUncached(DynamicObject encoding) {
            return isAsciiCompatible(encoding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_LOADED_CLASSES_CACHE;
        }

        protected static boolean isAsciiCompatible(DynamicObject encoding) {
            assert RubyGuards.isRubyEncoding(encoding);

            return EncodingOperations.getEncoding(encoding).isAsciiCompatible();
        }
    }

    @CoreMethod(names = "compatible?", onSingleton = true, required = 2)
    public abstract static class CompatibleQueryNode extends CoreMethodArrayArgumentsNode {

        @Child private ToEncodingNode toEncodingNode;

        public CompatibleQueryNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            toEncodingNode = ToEncodingNode.create();
        }

        public abstract DynamicObject executeCompatibleQuery(Object string, Object other);

        @Specialization(guards = {
                "getEncoding(first) == getEncoding(second)",
                "getEncoding(first) == cachedEncoding",
        }, limit = "getCacheLimit()")
        public DynamicObject isCompatibleCached(Object first, Object second,
                                                @Cached("getEncoding(first)") Encoding cachedEncoding,
                                                @Cached("getRubyEncoding(cachedEncoding)") DynamicObject result) {
            return result;
        }

        @Specialization(guards = "getEncoding(first) == getEncoding(second)", contains = "isCompatibleCached")
        public DynamicObject isCompatibleUncached(Object first, Object second) {
            return getRubyEncoding(getEncoding(first));
        }

        @Specialization(guards = {
                "firstEncoding != secondEncoding",
                "isRubyString(first)",
                "isRubyString(second)",
                "isEmpty(first) == isFirstEmpty",
                "isEmpty(second) == isSecondEmpty",
                "getCodeRange(first) == firstCodeRange",
                "getCodeRange(second) == secondCodeRange",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding"
        }, limit = "getCacheLimit()")
        public DynamicObject isCompatibleStringStringCached(DynamicObject first, DynamicObject second,
                                                     @Cached("getEncoding(first)") Encoding firstEncoding,
                                                     @Cached("getEncoding(second)") Encoding secondEncoding,
                                                     @Cached("isEmpty(first)") boolean isFirstEmpty,
                                                     @Cached("isEmpty(second)") boolean isSecondEmpty,
                                                     @Cached("getCodeRange(first)") CodeRange firstCodeRange,
                                                     @Cached("getCodeRange(second)") CodeRange secondCodeRange,
                                                     @Cached("isCompatibleStringStringUncached(first, second)") DynamicObject rubyEncoding) {
            return rubyEncoding;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "isRubyString(first)",
                "isRubyString(second)",
        }, contains = "isCompatibleStringStringCached")
        public DynamicObject isCompatibleStringStringUncached(DynamicObject first, DynamicObject second) {
            final Encoding compatibleEncoding = compatibleEncodingForStrings(first, second);

            if (compatibleEncoding != null) {
                return getContext().getEncodingManager().getRubyEncoding(compatibleEncoding);
            } else {
                return nil();
            }
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "isRubyString(first)",
                "!isRubyString(second)",
                "getCodeRange(first) == codeRange",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding"
        }, limit = "getCacheLimit()")
        public DynamicObject isCompatibleStringObjectCached(DynamicObject first, Object second,
                                                            @Cached("getEncoding(first)") Encoding firstEncoding,
                                                            @Cached("getEncoding(second)") Encoding secondEncoding,
                                                            @Cached("getCodeRange(first)") CodeRange codeRange,
                                                            @Cached("isCompatibleStringObjectUncached(first, second)") DynamicObject result) {
            return result;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "isRubyString(first)",
                "!isRubyString(second)"
        }, contains = "isCompatibleStringObjectCached")
        public DynamicObject isCompatibleStringObjectUncached(DynamicObject first, Object second) {
            final Encoding firstEncoding = getEncoding(first);
            final Encoding secondEncoding = getEncoding(second);

            if (secondEncoding == null) {
                return nil();
            }

            if (! firstEncoding.isAsciiCompatible() || ! secondEncoding.isAsciiCompatible()) {
                return nil();
            }

            if (secondEncoding == USASCIIEncoding.INSTANCE) {
                return getContext().getEncodingManager().getRubyEncoding(firstEncoding);
            }

            if (getCodeRange(first) == CodeRange.CR_7BIT) {
                return getContext().getEncodingManager().getRubyEncoding(secondEncoding);
            }

            return nil();
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "!isRubyString(first)",
                "isRubyString(second)"
        })
        public DynamicObject isCompatibleObjectString(Object first, DynamicObject second) {
            return isCompatibleStringObjectUncached(second, first);
        }

        @Specialization(guards = {
                "firstEncoding != secondEncoding",
                "!isRubyString(first)",
                "!isRubyString(second)",
                "firstEncoding != null",
                "secondEncoding != null",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding",
        }, limit = "getCacheLimit()")
        public DynamicObject isCompatibleObjectObjectCached(Object first, Object second,
                                                 @Cached("getEncoding(first)") Encoding firstEncoding,
                                                 @Cached("getEncoding(second)") Encoding secondEncoding,
                                                 @Cached("getCompatibleEncoding(getContext(), firstEncoding, secondEncoding)") DynamicObject result) {

            return result;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "!isRubyString(first)",
                "!isRubyString(second)"
        }, contains = "isCompatibleObjectObjectCached")
        public DynamicObject isCompatibleObjectObjectUncached(Object first, Object second) {
            final Encoding firstEncoding = getEncoding(first);
            final Encoding secondEncoding = getEncoding(second);

            return getCompatibleEncoding(getContext(), firstEncoding, secondEncoding);
        }

        private static Encoding compatibleEncodingForStrings(DynamicObject first, DynamicObject second) {
            // Taken from org.jruby.RubyEncoding#areCompatible.

            assert RubyGuards.isRubyString(first);
            assert RubyGuards.isRubyString(second);

            final Rope firstRope = StringOperations.rope(first);
            final Rope secondRope = StringOperations.rope(second);

            return compatibleEncodingForRopes(firstRope, secondRope);
        }

        @TruffleBoundary
        private static Encoding compatibleEncodingForRopes(Rope firstRope, Rope secondRope) {
            // Taken from org.jruby.RubyEncoding#areCompatible.

            final Encoding firstEncoding = firstRope.getEncoding();
            final Encoding secondEncoding = secondRope.getEncoding();

            if (secondRope.isEmpty()) return firstEncoding;
            if (firstRope.isEmpty()) {
                return firstEncoding.isAsciiCompatible() && (secondRope.getCodeRange() == CodeRange.CR_7BIT) ? firstEncoding : secondEncoding;
            }

            if (!firstEncoding.isAsciiCompatible() || !secondEncoding.isAsciiCompatible()) return null;

            if (firstRope.getCodeRange() != secondRope.getCodeRange()) {
                if (firstRope.getCodeRange() == CodeRange.CR_7BIT) return secondEncoding;
                if (secondRope.getCodeRange() == CodeRange.CR_7BIT) return firstEncoding;
            }
            if (secondRope.getCodeRange() == CodeRange.CR_7BIT) return firstEncoding;
            if (firstRope.getCodeRange() == CodeRange.CR_7BIT) return secondEncoding;

            return null;
        }

        @TruffleBoundary
        private static Encoding areCompatible(Encoding enc1, Encoding enc2) {
            assert enc1 != enc2;

            if (enc1 == null || enc2 == null) return null;

            if (!enc1.isAsciiCompatible() || !enc2.isAsciiCompatible()) return null;

            if (enc2 instanceof USASCIIEncoding) return enc1;
            if (enc1 instanceof USASCIIEncoding) return enc2;

            return null;
        }

        protected static DynamicObject getCompatibleEncoding(RubyContext context, Encoding first, Encoding second) {
            final Encoding compatibleEncoding = areCompatible(first, second);

            if (compatibleEncoding != null) {
                return context.getEncodingManager().getRubyEncoding(compatibleEncoding);
            } else {
                return context.getCoreLibrary().getNilObject();
            }
        }

        protected boolean isEmpty(Object string) {
            // The Truffle DSL generator will calculate @Cached values used in guards above all guards. In practice,
            // this guard is only used on Ruby strings, but the method must handle any object type because of the form
            // of the generated code. If the object is not a Ruby string, the resulting value is never used.
            if (RubyGuards.isRubyString(string)) {
                return StringOperations.rope((DynamicObject) string).isEmpty();
            }

            return false;
        }

        protected CodeRange getCodeRange(Object string) {
            // The Truffle DSL generator will calculate @Cached values used in guards above all guards. In practice,
            // this guard is only used on Ruby strings, but the method must handle any object type because of the form
            // of the generated code. If the object is not a Ruby string, the resulting value is never used.
            if (RubyGuards.isRubyString(string)) {
                return StringOperations.rope((DynamicObject) string).getCodeRange();
            }

            return CodeRange.CR_UNKNOWN;
        }

        protected Encoding getEncoding(Object value) {
            return toEncodingNode.executeToEncoding(value);
        }

        protected DynamicObject getRubyEncoding(Encoding value) {
            if (value == null) {
                return nil();
            }

            return getContext().getEncodingManager().getRubyEncoding(value);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_COMPATIBILE_QUERY_CACHE;
        }

    }

    @NonStandard
    @CoreMethod(names = "default_external_jruby=", onSingleton = true, required = 1)
    public abstract static class SetDefaultExternalNode extends CoreMethodArrayArgumentsNode {

        @Child private ToStrNode toStrNode;

        @TruffleBoundary
        @Specialization(guards = "isRubyEncoding(encoding)")
        public DynamicObject defaultExternalEncoding(DynamicObject encoding) {
            getContext().getJRubyRuntime().setDefaultExternalEncoding(EncodingOperations.getEncoding(encoding));

            return encoding;
        }

        @TruffleBoundary
        @Specialization(guards = "isRubyString(encodingString)")
        public DynamicObject defaultExternal(DynamicObject encodingString) {
            final DynamicObject rubyEncoding = getContext().getEncodingManager().getRubyEncoding(encodingString.toString());
            getContext().getJRubyRuntime().setDefaultExternalEncoding(EncodingOperations.getEncoding(rubyEncoding));

            return rubyEncoding;
        }

        @TruffleBoundary
        @Specialization(guards = "isNil(nil)")
        public DynamicObject defaultExternal(Object nil) {
            throw new RaiseException(coreExceptions().argumentError("default external can not be nil", this));
        }

        @Specialization(guards = { "!isRubyEncoding(encoding)", "!isRubyString(encoding)", "!isNil(encoding)" })
        public DynamicObject defaultExternal(VirtualFrame frame, Object encoding) {
            if (toStrNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toStrNode = insert(ToStrNodeGen.create(getContext(), null, null));
            }

            return defaultExternal(toStrNode.executeToStr(frame, encoding));
        }

    }

    @NonStandard
    @CoreMethod(names = "default_internal_jruby=", onSingleton = true, required = 1)
    public abstract static class SetDefaultInternalNode extends CoreMethodArrayArgumentsNode {

        @Child private ToStrNode toStrNode;

        @TruffleBoundary
        @Specialization(guards = "isRubyEncoding(encoding)")
        public DynamicObject defaultInternal(DynamicObject encoding) {
            getContext().getJRubyRuntime().setDefaultInternalEncoding(EncodingOperations.getEncoding(encoding));

            return encoding;
        }

        @TruffleBoundary
        @Specialization(guards = "isNil(encoding)")
        public DynamicObject defaultInternal(Object encoding) {
            getContext().getJRubyRuntime().setDefaultInternalEncoding(null);

            return nil();
        }

        @Specialization(guards = { "!isRubyEncoding(encoding)", "!isNil(encoding)" })
        public DynamicObject defaultInternal(VirtualFrame frame, Object encoding) {
            if (toStrNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toStrNode = insert(ToStrNodeGen.create(getContext(), null, null));
            }

            final DynamicObject encodingName = toStrNode.executeToStr(frame, encoding);
            getContext().getJRubyRuntime().setDefaultInternalEncoding(EncodingOperations.getEncoding(getContext().getEncodingManager().getRubyEncoding(encodingName.toString())));

            return encodingName;
        }

    }

    @CoreMethod(names = "list", onSingleton = true)
    public abstract static class ListNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject list() {
            final Object[] encodingsList = getContext().getEncodingManager().getEncodingList();
            return createArray(encodingsList, encodingsList.length);
        }
    }


    @CoreMethod(names = "locale_charmap", onSingleton = true)
    public abstract static class LocaleCharacterMapNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject localeCharacterMap() {
            final DynamicObject rubyEncoding = getContext().getEncodingManager().getRubyEncoding(getContext().getEncodingManager().getLocaleEncoding());

            return Layouts.ENCODING.getName(rubyEncoding);
        }
    }

    @CoreMethod(names = "dummy?")
    public abstract static class DummyNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "encoding == cachedEncoding", limit = "getCacheLimit()")
        public boolean isDummyCached(DynamicObject encoding,
                                     @Cached("encoding") DynamicObject cachedEncoding,
                                     @Cached("isDummy(cachedEncoding)") boolean isDummy) {
            return isDummy;
        }

        @Specialization(contains = "isDummyCached")
        public boolean isDummyUncached(DynamicObject encoding) {
            return isDummy(encoding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_LOADED_CLASSES_CACHE;
        }

        protected static boolean isDummy(DynamicObject encoding) {
            assert RubyGuards.isRubyEncoding(encoding);

            return Layouts.ENCODING.getDummy(encoding);
        }
    }

    @NonStandard
    @CoreMethod(names = "each_alias", onSingleton = true, visibility = Visibility.PRIVATE, needsBlock = true)
    public abstract static class EachAliasNode extends YieldingCoreMethodNode {

        @Specialization
        public DynamicObject eachAlias(VirtualFrame frame, DynamicObject block) {
            CompilerAsserts.neverPartOfCompilation();
            for (HashEntry<Entry> entry : EncodingDB.getAliases().entryIterator()) {
                final CaseInsensitiveBytesHashEntry<Entry> e = (CaseInsensitiveBytesHashEntry<Entry>) entry;
                final ByteList aliasName = new ByteList(e.bytes, e.p, e.end - e.p, USASCIIEncoding.INSTANCE, false);
                yield(frame, block, createString(aliasName), entry.value.getIndex());
            }
            return nil();
        }
    }

    @NonStandard
    @CoreMethod(names = "get_default_encoding", onSingleton = true, visibility = Visibility.PRIVATE, required = 1)
    public abstract static class GetDefaultEncodingNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isRubyString(name)")
        public DynamicObject getDefaultEncoding(DynamicObject name) {
            final Encoding encoding = getEncoding(StringOperations.getString(name));
            if (encoding == null) {
                return nil();
            } else {
                return getContext().getEncodingManager().getRubyEncoding(encoding);
            }
        }

        @TruffleBoundary
        private Encoding getEncoding(String name) {
            switch (name) {
                case "internal":
                    return getContext().getJRubyRuntime().getDefaultInternalEncoding();
                case "external":
                    return getContext().getJRubyRuntime().getDefaultExternalEncoding();
                case "locale":
                case "filesystem":
                    return getContext().getEncodingManager().getLocaleEncoding();
                default:
                    throw new UnsupportedOperationException();
            }
        }
    }

    @CoreMethod(names = { "name", "to_s" })
    public abstract static class ToSNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject toS(DynamicObject encoding) {
            return Layouts.ENCODING.getName(encoding);
        }

    }

    @CoreMethod(names = "allocate", constructor = true)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            throw new RaiseException(coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

    @Primitive(name = "encoding_get_object_encoding", needsSelf = false)
    public static abstract class EncodingGetObjectEncodingNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyString(string)")
        public DynamicObject encodingGetObjectEncodingString(DynamicObject string) {
            return getContext().getEncodingManager().getRubyEncoding(Layouts.STRING.getRope(string).getEncoding());
        }

        @Specialization(guards = "isRubySymbol(symbol)")
        public DynamicObject encodingGetObjectEncodingSymbol(DynamicObject symbol) {
            return getContext().getEncodingManager().getRubyEncoding(Layouts.SYMBOL.getRope(symbol).getEncoding());
        }

        @Specialization(guards = "isRubyEncoding(encoding)")
        public DynamicObject encodingGetObjectEncoding(DynamicObject encoding) {
            return encoding;
        }

        @Specialization(guards = "isRubyRegexp(regexp)")
        public DynamicObject encodingGetObjectEncodingRegexp(DynamicObject regexp) {
            return getContext().getEncodingManager().getRubyEncoding(Layouts.REGEXP.getSource(regexp).getEncoding());
        }

        @Specialization(guards = {"!isRubyString(object)", "!isRubySymbol(object)", "!isRubyEncoding(object)", "!isRubyRegexp(object)"})
        public DynamicObject encodingGetObjectEncodingNil(DynamicObject object) {
            // TODO(CS, 26 Jan 15) something to do with __encoding__ here?
            return nil();
        }

    }

    @Primitive(name = "encoding_replicate")
    public static abstract class EncodingReplicateNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyString(nameObject)")
        public DynamicObject encodingReplicate(VirtualFrame frame, DynamicObject self, DynamicObject nameObject, @Cached("new()") SnippetNode snippetNode) {
            final String name = StringOperations.getString(nameObject);
            final Encoding encoding = EncodingOperations.getEncoding(self);

            final DynamicObject newEncoding = getContext().getEncodingManager().replicateEncoding(encoding, name);
            if (newEncoding == null) {
                throw new RaiseException(coreExceptions().argumentErrorEncodingAlreadyRegistered(name, this));
            }

            final Entry entry = EncodingDB.getEncodings().get(name.getBytes());
            snippetNode.execute(frame, "Encoding::EncodingMap[enc.name.upcase.to_sym] = [nil, index]", "enc", newEncoding, "index", entry.getIndex());
            return newEncoding;
        }

    }

    @Primitive(name = "encoding_get_encoding_by_index", needsSelf = false)
    public static abstract class EncodingGetObjectEncodingByIndexNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public DynamicObject encodingGetObjectEncodingByIndex(int index) {
            return getContext().getEncodingManager().getRubyEncoding(index);
        }

    }

    @NodeChildren({ @NodeChild("first"), @NodeChild("second") })
    public static abstract class CheckEncodingNode extends RubyNode {

        @Child private EncodingNodes.CompatibleQueryNode compatibleQueryNode;
        @Child private ToEncodingNode toEncodingNode;

        public CheckEncodingNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            compatibleQueryNode = EncodingNodesFactory.CompatibleQueryNodeFactory.create(context, sourceSection, new RubyNode[] {});
            toEncodingNode = ToEncodingNode.create();
        }

        public abstract Encoding executeCheckEncoding(Object first, Object second);

        @Specialization
        public Encoding checkEncoding(Object first, Object second,
                                      @Cached("create()") BranchProfile errorProfile) {
            final DynamicObject rubyEncoding = compatibleQueryNode.executeCompatibleQuery(first, second);

            if (rubyEncoding == nil()) {
                errorProfile.enter();
                throw new RaiseException(getContext().getCoreExceptions().encodingCompatibilityErrorIncompatible(
                        toEncodingNode.executeToEncoding(first), toEncodingNode.executeToEncoding(second), this));
            }

            return toEncodingNode.executeToEncoding(rubyEncoding);
        }

    }

}
