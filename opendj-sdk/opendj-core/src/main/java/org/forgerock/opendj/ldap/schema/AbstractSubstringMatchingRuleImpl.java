/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2009 Sun Microsystems, Inc.
 *      Portions copyright 2014 ForgeRock AS
 */
package org.forgerock.opendj.ldap.schema;

import static com.forgerock.opendj.ldap.CoreMessages.*;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.Assertion;
import org.forgerock.opendj.ldap.ByteSequence;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.ByteStringBuilder;
import org.forgerock.opendj.ldap.ConditionResult;
import org.forgerock.opendj.ldap.DecodeException;
import org.forgerock.opendj.ldap.spi.IndexQueryFactory;
import org.forgerock.opendj.ldap.spi.Indexer;
import org.forgerock.opendj.ldap.spi.IndexingOptions;

import com.forgerock.opendj.util.SubstringReader;

/**
 * This class implements a default substring matching rule that matches
 * normalized substring assertion values in byte order.
 */
abstract class AbstractSubstringMatchingRuleImpl extends AbstractMatchingRuleImpl {

    /**
     * Default assertion implementation for substring matching rules.
     * For example, with the assertion value "initial*any1*any2*any3*final",
     * the assertion will be decomposed like this:
     * <ul>
     * <li>normInitial will contain "initial"</li>
     * <li>normAnys will contain [ "any1", "any2", "any3" ]</li>
     * <li>normFinal will contain "final"</li>
     * </ul>
     */
    static final class DefaultSubstringAssertion implements Assertion {
        /** Normalized substring for the text before the first '*' character. */
        private final ByteString normInitial;
        /** Normalized substrings for all text chunks in between '*' characters. */
        private final ByteString[] normAnys;
        /** Normalized substring for the text after the last '*' character. */
        private final ByteString normFinal;

        private DefaultSubstringAssertion(final ByteString normInitial,
                final ByteString[] normAnys, final ByteString normFinal) {
            this.normInitial = normInitial;
            this.normAnys = normAnys;
            this.normFinal = normFinal;
        }

        /** {@inheritDoc} */
        @Override
        public ConditionResult matches(final ByteSequence normalizedAttributeValue) {
            final int valueLength = normalizedAttributeValue.length();

            int pos = 0;
            if (normInitial != null) {
                final int initialLength = normInitial.length();
                if (initialLength > valueLength) {
                    return ConditionResult.FALSE;
                }

                for (; pos < initialLength; pos++) {
                    if (normInitial.byteAt(pos) != normalizedAttributeValue.byteAt(pos)) {
                        return ConditionResult.FALSE;
                    }
                }
            }

            if (normAnys != null && normAnys.length != 0) {
                for (final ByteSequence element : normAnys) {
                    final int anyLength = element.length();
                    if (anyLength == 0) {
                        continue;
                    }
                    final int end = valueLength - anyLength;
                    boolean match = false;
                    for (; pos <= end; pos++) {
                        if (element.byteAt(0) == normalizedAttributeValue.byteAt(pos)) {
                            boolean subMatch = true;
                            for (int i = 1; i < anyLength; i++) {
                                if (element.byteAt(i) != normalizedAttributeValue.byteAt(pos + i)) {
                                    subMatch = false;
                                    break;
                                }
                            }

                            if (subMatch) {
                                match = subMatch;
                                break;
                            }
                        }
                    }

                    if (match) {
                        pos += anyLength;
                    } else {
                        return ConditionResult.FALSE;
                    }
                }
            }

            if (normFinal != null) {
                final int finalLength = normFinal.length();

                if (valueLength - finalLength < pos) {
                    return ConditionResult.FALSE;
                }

                pos = valueLength - finalLength;
                for (int i = 0; i < finalLength; i++, pos++) {
                    if (normFinal.byteAt(i) != normalizedAttributeValue.byteAt(pos)) {
                        return ConditionResult.FALSE;
                    }
                }
            }

            return ConditionResult.TRUE;
        }

        /** {@inheritDoc} */
        @Override
        public <T> T createIndexQuery(IndexQueryFactory<T> factory) throws DecodeException {
            final Collection<T> subqueries = new LinkedList<T>();
            if (normInitial != null) {
                // relies on the fact that equality indexes are also ordered
                subqueries.add(rangeMatch(factory, "equality", normInitial));
            }
            if (normAnys != null) {
                for (ByteString normAny : normAnys) {
                    substringMatch(factory, normAny, subqueries);
                }
            }
            if (normFinal != null) {
                substringMatch(factory, normFinal, subqueries);
            }
            if (normInitial != null) {
                // Add this one last to minimize the risk to run the same search twice
                // (possible overlapping with the use of equality index at the start of this method)
                substringMatch(factory, normInitial, subqueries);
            }
            return factory.createIntersectionQuery(subqueries);
        }

        private <T> T rangeMatch(IndexQueryFactory<T> factory, String indexID, ByteSequence lower) {
            // Iterate through all the keys that have this value as the prefix.

            // Set the upper bound for a range search.
            // We need a key for the upper bound that is of equal length
            // but slightly greater than the lower bound.
            final ByteStringBuilder upper = new ByteStringBuilder(lower);

            for (int i = upper.length() - 1; i >= 0; i--) {
                if (upper.byteAt(i) == 0xFF) {
                    // We have to carry the overflow to the more significant byte.
                    upper.setByte(i, (byte) 0);
                } else {
                    // No overflow, we can stop.
                    upper.setByte(i, (byte) (upper.byteAt(i) + 1));
                    break;
                }
            }

            // Read the range: lower <= keys < upper.
            return factory.createRangeMatchQuery(indexID, lower, upper, true, false);
        }

        private <T> void substringMatch(final IndexQueryFactory<T> factory, final ByteString normSubstring,
                final Collection<T> subqueries) {
            int substrLength = factory.getIndexingOptions().substringKeySize();

            // There are two cases, depending on whether the user-provided
            // substring is smaller than the configured index substring length or not.
            if (normSubstring.length() < substrLength) {
                subqueries.add(rangeMatch(factory, "substring", normSubstring));
            } else {
                // Break the value up into fragments of length equal to the
                // index substring length, and read those keys.

                // Eliminate duplicates by putting the keys into a set.
                final TreeSet<ByteSequence> substringKeys = new TreeSet<ByteSequence>();

                // Example: The value is ABCDE and the substring length is 3.
                // We produce the keys ABC BCD CDE.
                for (int first = 0, last = substrLength;
                     last <= normSubstring.length(); first++, last++) {
                    substringKeys.add(normSubstring.subSequence(first, first + substrLength));
                }

                for (ByteSequence key : substringKeys) {
                    subqueries.add(factory.createExactMatchQuery("substring", key));
                }
            }
        }

    }

    final class SubstringIndexer implements Indexer {

        /** {@inheritDoc} */
        @Override
        public void createKeys(Schema schema, ByteSequence value, IndexingOptions options, Collection<ByteString> keys)
                throws DecodeException {
            final ByteString normValue = normalizeAttributeValue(schema, value);
            final int substringKeySize = options.substringKeySize();

            // Example: The value is ABCDE and the substring length is 3.
            // We produce the keys ABC BCD CDE DE E
            // To find values containing a short substring such as DE,
            // iterate through keys with prefix DE. To find values
            // containing a longer substring such as BCDE, read keys BCD and CDE.
            for (int i = 0, remain = normValue.length(); remain > 0; i++, remain--) {
                int len = Math.min(substringKeySize, remain);
                keys.add(normValue.subSequence(i, i  + len));
            }
        }

        /** {@inheritDoc} */
        @Override
        public String getIndexID() {
            return "substring";
        }
    }

    private final Collection<? extends Indexer> indexers =
              Collections.singleton(new SubstringIndexer());

    AbstractSubstringMatchingRuleImpl() {
        // Nothing to do.
    }

    /** {@inheritDoc} */
    @Override
    public Assertion getAssertion(final Schema schema, final ByteSequence assertionValue)
            throws DecodeException {
        if (assertionValue.length() == 0) {
            throw DecodeException.error(WARN_ATTR_SYNTAX_SUBSTRING_EMPTY.get());
        }

        ByteSequence initialString = null;
        ByteSequence finalString = null;
        List<ByteSequence> anyStrings = null;

        final String valueString = assertionValue.toString();
        if (valueString.length() == 1 && valueString.charAt(0) == '*') {
            return getSubstringAssertion(schema, initialString, anyStrings, finalString);
        }

        final char[] escapeChars = new char[] { '*' };
        final SubstringReader reader = new SubstringReader(valueString);

        ByteString bytes = evaluateEscapes(reader, escapeChars, false);
        if (bytes.length() > 0) {
            initialString = normalizeSubString(schema, bytes);
        }
        if (reader.remaining() == 0) {
            throw DecodeException.error(WARN_ATTR_SYNTAX_SUBSTRING_NO_WILDCARDS.get(assertionValue));
        }
        while (true) {
            reader.read();
            bytes = evaluateEscapes(reader, escapeChars, false);
            if (reader.remaining() > 0) {
                if (bytes.length() == 0) {
                    throw DecodeException.error(WARN_ATTR_SYNTAX_SUBSTRING_CONSECUTIVE_WILDCARDS
                            .get(assertionValue, reader.pos()));
                }
                if (anyStrings == null) {
                    anyStrings = new LinkedList<ByteSequence>();
                }
                anyStrings.add(normalizeSubString(schema, bytes));
            } else {
                if (bytes.length() > 0) {
                    finalString = normalizeSubString(schema, bytes);
                }
                break;
            }
        }

        return getSubstringAssertion(schema, initialString, anyStrings, finalString);
    }

    /** {@inheritDoc} */
    @Override
    public Assertion getSubstringAssertion(final Schema schema, final ByteSequence subInitial,
            final List<? extends ByteSequence> subAnyElements, final ByteSequence subFinal)
            throws DecodeException {
        final ByteString normInitial =
                subInitial == null ? null : normalizeSubString(schema, subInitial);

        ByteString[] normAnys = null;
        if (subAnyElements != null && !subAnyElements.isEmpty()) {
            normAnys = new ByteString[subAnyElements.size()];
            for (int i = 0; i < subAnyElements.size(); i++) {
                normAnys[i] = normalizeSubString(schema, subAnyElements.get(i));
            }
        }
        final ByteString normFinal = subFinal == null ? null : normalizeSubString(schema, subFinal);

        return new DefaultSubstringAssertion(normInitial, normAnys, normFinal);
    }

    ByteString normalizeSubString(final Schema schema, final ByteSequence value)
            throws DecodeException {
        return normalizeAttributeValue(schema, value);
    }

    private char evaluateEscapedChar(final SubstringReader reader, final char[] escapeChars)
            throws DecodeException {
        final char c1 = reader.read();
        byte b;
        switch (c1) {
        case '0':
            b = 0x00;
            break;
        case '1':
            b = 0x10;
            break;
        case '2':
            b = 0x20;
            break;
        case '3':
            b = 0x30;
            break;
        case '4':
            b = 0x40;
            break;
        case '5':
            b = 0x50;
            break;
        case '6':
            b = 0x60;
            break;
        case '7':
            b = 0x70;
            break;
        case '8':
            b = (byte) 0x80;
            break;
        case '9':
            b = (byte) 0x90;
            break;
        case 'A':
        case 'a':
            b = (byte) 0xA0;
            break;
        case 'B':
        case 'b':
            b = (byte) 0xB0;
            break;
        case 'C':
        case 'c':
            b = (byte) 0xC0;
            break;
        case 'D':
        case 'd':
            b = (byte) 0xD0;
            break;
        case 'E':
        case 'e':
            b = (byte) 0xE0;
            break;
        case 'F':
        case 'f':
            b = (byte) 0xF0;
            break;
        default:
            if (c1 == 0x5C) {
                return c1;
            }
            if (escapeChars != null) {
                for (final char escapeChar : escapeChars) {
                    if (c1 == escapeChar) {
                        return c1;
                    }
                }
            }
            final LocalizableMessage message = ERR_INVALID_ESCAPE_CHAR.get(reader.getString(), c1);
            throw DecodeException.error(message);
        }

        // The two positions must be the hex characters that
        // comprise the escaped value.
        if (reader.remaining() == 0) {
            final LocalizableMessage message =
                    ERR_HEX_DECODE_INVALID_LENGTH.get(reader.getString());

            throw DecodeException.error(message);
        }

        final char c2 = reader.read();
        switch (c2) {
        case '0':
            // No action required.
            break;
        case '1':
            b |= 0x01;
            break;
        case '2':
            b |= 0x02;
            break;
        case '3':
            b |= 0x03;
            break;
        case '4':
            b |= 0x04;
            break;
        case '5':
            b |= 0x05;
            break;
        case '6':
            b |= 0x06;
            break;
        case '7':
            b |= 0x07;
            break;
        case '8':
            b |= 0x08;
            break;
        case '9':
            b |= 0x09;
            break;
        case 'A':
        case 'a':
            b |= 0x0A;
            break;
        case 'B':
        case 'b':
            b |= 0x0B;
            break;
        case 'C':
        case 'c':
            b |= 0x0C;
            break;
        case 'D':
        case 'd':
            b |= 0x0D;
            break;
        case 'E':
        case 'e':
            b |= 0x0E;
            break;
        case 'F':
        case 'f':
            b |= 0x0F;
            break;
        default:
            final LocalizableMessage message =
                    ERR_HEX_DECODE_INVALID_CHARACTER.get(new String(new char[] { c1, c2 }), c1);
            throw DecodeException.error(message);
        }
        return (char) b;
    }

    private ByteString evaluateEscapes(final SubstringReader reader, final char[] escapeChars,
            final boolean trim) throws DecodeException {
        return evaluateEscapes(reader, escapeChars, escapeChars, trim);
    }

    private ByteString evaluateEscapes(final SubstringReader reader, final char[] escapeChars,
            final char[] delimiterChars, final boolean trim) throws DecodeException {
        int length = 0;
        int lengthWithoutSpace = 0;
        char c;
        ByteStringBuilder valueBuffer = null;

        if (trim) {
            reader.skipWhitespaces();
        }

        reader.mark();
        while (reader.remaining() > 0) {
            c = reader.read();
            if (c == 0x5C /* The backslash character */) {
                if (valueBuffer == null) {
                    valueBuffer = new ByteStringBuilder();
                }
                valueBuffer.append(reader.read(length));
                valueBuffer.append(evaluateEscapedChar(reader, escapeChars));
                reader.mark();
                length = lengthWithoutSpace = 0;
            }
            if (delimiterChars != null) {
                for (final char delimiterChar : delimiterChars) {
                    if (c == delimiterChar) {
                        reader.reset();
                        if (valueBuffer != null) {
                            if (trim) {
                                valueBuffer.append(reader.read(lengthWithoutSpace));
                            } else {
                                valueBuffer.append(reader.read(length));
                            }
                            return valueBuffer.toByteString();
                        } else {
                            if (trim) {
                                if (lengthWithoutSpace > 0) {
                                    return ByteString.valueOf(reader.read(lengthWithoutSpace));
                                }
                                return ByteString.empty();
                            }
                            if (length > 0) {
                                return ByteString.valueOf(reader.read(length));
                            }
                            return ByteString.empty();
                        }
                    }
                }
            }
            length++;
            if (c != ' ') {
                lengthWithoutSpace = length;
            } else {
                lengthWithoutSpace++;
            }
        }

        reader.reset();
        if (valueBuffer != null) {
            if (trim) {
                valueBuffer.append(reader.read(lengthWithoutSpace));
            } else {
                valueBuffer.append(reader.read(length));
            }
            return valueBuffer.toByteString();
        } else {
            if (trim) {
                if (lengthWithoutSpace > 0) {
                    return ByteString.valueOf(reader.read(lengthWithoutSpace));
                }
                return ByteString.empty();
            }
            if (length > 0) {
                return ByteString.valueOf(reader.read(length));
            }
            return ByteString.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Collection<? extends Indexer> getIndexers() {
        return indexers;
    }

}
