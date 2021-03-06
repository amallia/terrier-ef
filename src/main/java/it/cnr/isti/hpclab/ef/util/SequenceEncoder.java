/*
 * Elias-Fano compression for Terrier 5
 *
 * Copyright (C) 2018-2018 Nicola Tonellotto 
 *
 *  This library is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as published by the Free
 *  Software Foundation; either version 3 of the License, or (at your option)
 *  any later version.
 *
 *  This library is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

/*
 * The original source code is it.unimi.di.big.mg4j.index.QuasiSuccinctIndexWriter class (Accumulator)
 * 
 * http://mg4j.di.unimi.it/docs-big/it/unimi/di/big/mg4j/index/QuasiSuccinctIndexWriter.html
 * 
 * being part of
 *  		 
 * MG4J: Managing Gigabytes for Java (big)
 *
 * Copyright (C) 2012 Sebastiano Vigna 
 */
package it.cnr.isti.hpclab.ef.util;

import java.io.Closeable;
import java.io.IOException;

/**
 * This class implements an encoder of sequences of natural numbers according to Elias-Fano and can dump it to a bit file,
 * according to an internally hardcoded structure (pointers, lowers, uppers).
 */
public class SequenceEncoder implements Closeable 
{
	/** The minimum size in bytes of a {@link LongWordCache}. */
	private static final int MIN_CACHE_SIZE = 16;

	/** The accumulator for pointers (to zeros or ones). */
	private final LongWordCache pointers;
	/** The accumulator for high bits. */
	private final LongWordCache upperBits;
	/** The accumulator for low bits. */
	private final LongWordCache lowerBits;

	/** If true, {@link #add(long)} does not accept zeroes. */
	private boolean strict;

	/** The number of lower bits. */
	private int l;
	/** A mask extracting the {@link #l} lower bits. */
	private long lowerBitsMask;
	/** The number of elements that will be added to this list. */
	private long length;
	/** The current length of the list. */
	private long currentLength;
	/** The current prefix sum (decremented by {@link #currentLength} if {@link #strict} is true). */
	private long currentPrefixSum;
	/** An upper bound to the sum of all values that will be added to the list (decremented by {@link #currentLength} if {@link #strict} is true). */
	private long correctedUpperBound;
	/** The logarithm of the indexing quantum. */
	private int log2Quantum;
	/** The indexing quantum. */
	private long quantum;
	/** The size of a pointer (the ceiling of the logarithm of {@link #maxUpperBits}). */
	private int pointerSize;
	/** The mask to decide whether to quantize. */
	private long quantumMask;
	/** Whether we should index ones or zeroes. */
	private boolean indexZeroes;
	/** The last position where a one was set. */
	private long lastOnePosition;
	/** The expected number of points. */
	private long expectedNumberOfPointers;
	/** The number of bits used for the upper-bits array. */
	public long bitsForUpperBits;
	/** The number of bits used for the lower-bits array. */
	public long bitsForLowerBits;
	/** The number of bits used for forward/skip pointers. */
	public long bitsForPointers;

	/**
	 * Constructor.
	 * @param bufferSize the size of the buffer in the file-backed caches used to perform encoding
	 * @param log2Quantum the base 2 logarithm of the quantum used to compute skip (or forward) pointer
	 * @throws IOException if something goes wrong
	 */
	public SequenceEncoder(int bufferSize, final int log2Quantum) throws IOException 
	{
		bufferSize = bufferSize & -bufferSize; // Ensure power of 2.
		/*
		 * Very approximately, half of the cache for lower, half for upper, and
		 * a small fraction (8/quantum) for pointers. This will generate a much
		 * larger cache than expected if quantum is very small.
		 */
		pointers  = new LongWordCache(Math.max(MIN_CACHE_SIZE, bufferSize >>> Math.max(3, log2Quantum - 3)), "pointers");
		lowerBits = new LongWordCache(Math.max(MIN_CACHE_SIZE, bufferSize / 2), "lower");
		upperBits = new LongWordCache(Math.max(MIN_CACHE_SIZE, bufferSize / 2), "upper");
	}

	/**
	 * Return the number of lower bits used in encoding so far.
	 * @return the number of lower bits used in encoding so far
	 */
	public int lowerBits() 
	{
		return l;
	}

	/**
	 * Return the size of a pointer (the ceiling of the logarithm of {@link #maxUpperBits}).
	 * @return The size of a pointer (the ceiling of the logarithm of {@link #maxUpperBits})
	 */
	public int pointerSize() 
	{
		return pointerSize;
	}

	/**
	 * Return the expected number of points.
	 * @return the expected number of points.
	 */
	public long numberOfPointers() 
	{
		return expectedNumberOfPointers;
	}

	/**
	 * Initialization of the encoder. Must be called before actual encoding begins.
	 * @param length the number of elements to encode
	 * @param upperBound the upper bound on the last element to encode
	 * @param strict if <code>true</code>  {@link #add(long)} does not accept zeroes.
	 * @param indexZeroes whether we should index ones or zeroes. if true, skip pointers are used; otherwise, forward pointers are used.
	 * @param log2Quantum the base 2 logarithm of the quantum used to compute skip (or forward) pointer
	 */
	public void init(final long length, final long upperBound, final boolean strict, final boolean indexZeroes, final int log2Quantum) 
	{
		this.indexZeroes = indexZeroes;
		this.log2Quantum = log2Quantum;
		this.length = length;
		this.strict = strict;
		quantum = 1L << log2Quantum;
		quantumMask = quantum - 1;
		pointers.clear();
		lowerBits.clear();
		upperBits.clear();
		correctedUpperBound = upperBound - (strict ? length : 0);
		final long correctedLength = length + (!strict && indexZeroes ? 1 : 0); // The length including the final terminator
		if (correctedUpperBound < 0)
			throw new IllegalArgumentException();

		currentPrefixSum = 0;
		currentLength = 0;
		lastOnePosition = -1;

		l = Utils.lowerBits(correctedLength, upperBound, strict);

		lowerBitsMask = (1L << l) - 1;

		pointerSize = Utils.pointerSize(correctedLength, upperBound, strict, indexZeroes);
		expectedNumberOfPointers = Utils.numberOfPointers(correctedLength, upperBound, log2Quantum, strict, indexZeroes);
	}

	/**
	 * Add a new natural number to the encode.
	 * @param x the natural number to add
	 * @throws IOException if something goes wrong
	 */
	public void add(final long x) throws IOException 
	{
		if (strict && x == 0)
			throw new IllegalArgumentException("Zeroes are not allowed.");
		currentPrefixSum += x - (strict ? 1 : 0);
		if (currentPrefixSum > correctedUpperBound)
			throw new IllegalArgumentException("Too large prefix sum: "	+ currentPrefixSum + " >= " + correctedUpperBound);
		if (l != 0)
			lowerBits.append(currentPrefixSum & lowerBitsMask, l);
		final long onePosition = (currentPrefixSum >>> l) + currentLength;

		upperBits.writeUnary((int) (onePosition - lastOnePosition - 1));

		if (indexZeroes) {
			long zeroesBefore = lastOnePosition - currentLength + 1;
			for (long position = lastOnePosition + (zeroesBefore & -1L << log2Quantum) + quantum - zeroesBefore; position < onePosition; position += quantum, zeroesBefore += quantum)
				pointers.append(position + 1, pointerSize);
		} else if ((currentLength + 1 & quantumMask) == 0)
			pointers.append(onePosition + 1, pointerSize);

		lastOnePosition = onePosition;
		currentLength++;
	}
	
	/**
	 * Dump the complete encoded sequence to a bit output stream.
	 * Could add last fictional document pointer equal to the number of documents.
	 * 
	 * @param lwobs the output bit stream where to dump
	 * @return the number of dumped bits
	 * @throws IOException if something goes wrong
	 */
	public long dump(final LongWordBitWriter lwobs) throws IOException 
	{
		if (currentLength != length)
			throw new IllegalStateException();
		if (!strict && indexZeroes) {
			// Add last fictional document pointer equal to the number of documents.
			add(correctedUpperBound - currentPrefixSum);
		}
		if (indexZeroes && pointerSize != 0)
			for (long actualPointers = pointers.length() / pointerSize; actualPointers++ < expectedNumberOfPointers;)
				pointers.append(0, pointerSize);

		bitsForPointers  = lwobs.append(pointers);
		bitsForLowerBits = lwobs.append(lowerBits);
		bitsForUpperBits = lwobs.append(upperBits);

		return bitsForLowerBits + bitsForUpperBits + bitsForPointers;
	}

	/** @inherited */
	@Override
	public void close() throws IOException 
	{
		pointers.close();
		upperBits.close();
		lowerBits.close();
	}
}