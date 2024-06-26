/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.ethereum.util.BIUtil.isIn20PercentRange;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Mikhail Kalinin
 * @since 15.10.2015
 */
class BIUtilTest {

    @Test
    void testIsIn20PercentRange() {

        assertTrue(isIn20PercentRange(BigInteger.valueOf(20000), BigInteger.valueOf(24000)));

        assertTrue(isIn20PercentRange(BigInteger.valueOf(24000), BigInteger.valueOf(20000)));

        assertFalse(isIn20PercentRange(BigInteger.valueOf(20000), BigInteger.valueOf(25000)));

        assertTrue(isIn20PercentRange(BigInteger.valueOf(20), BigInteger.valueOf(24)));

        assertTrue(isIn20PercentRange(BigInteger.valueOf(24), BigInteger.valueOf(20)));

        assertFalse(isIn20PercentRange(BigInteger.valueOf(20), BigInteger.valueOf(25)));

        assertTrue(isIn20PercentRange(BigInteger.ZERO, BigInteger.ZERO));

        assertFalse(isIn20PercentRange(BigInteger.ZERO, BigInteger.ONE));

        assertTrue(isIn20PercentRange(BigInteger.ONE, BigInteger.ZERO));
    }

    @Test // test isIn20PercentRange
    void test1() {
        assertFalse(isIn20PercentRange(BigInteger.ONE, BigInteger.valueOf(5)));
        assertTrue(isIn20PercentRange(BigInteger.valueOf(5), BigInteger.ONE));
        assertTrue(isIn20PercentRange(BigInteger.valueOf(5), BigInteger.valueOf(6)));
        assertFalse(isIn20PercentRange(BigInteger.valueOf(5), BigInteger.valueOf(7)));
    }
}
