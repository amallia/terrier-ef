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
package it.cnr.isti.hpclab.ef;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.structures.BitIndexPointer;
import org.terrier.structures.FSOMapFileLexiconOutputStream;
import org.terrier.structures.Index;
import org.terrier.structures.LexiconEntry;
import org.terrier.structures.LexiconOutputStream;
import org.terrier.structures.collections.FSOrderedMapFile;
import org.terrier.structures.postings.IterablePosting;
import org.terrier.structures.seralization.FixedSizeTextFactory;

import it.cnr.isti.hpclab.ef.structures.EFLexiconEntry;
import it.cnr.isti.hpclab.ef.util.IndexUtil;
import it.cnr.isti.hpclab.ef.util.LongWordBitWriter;
import it.cnr.isti.hpclab.ef.util.SequenceEncoder;

/**
 * This is a Elias-Fano compressor focusing on lexicon and posting lists only. It compresses only a range of input termids.
 * All lexicon entries have offsets aligned to this portion of the whole index only, and the docis/freqs files are closed at the end, so such files are byte-aligned.
 * This must be taken into account when merging.
 */
public class BasicCompressor implements Compressor
{
	protected static final Logger LOGGER = LoggerFactory.getLogger(BasicCompressor.class);
	protected static final int DEFAULT_CACHE_SIZE = 64 * 1024 * 1024;

	protected int LOG2QUANTUM;
	
	protected final String dst_index_path;
	protected final String dst_index_prefix;
	
	protected final Index src_index;
	protected final int num_docs;

	public BasicCompressor(final Index src_index, final String dst_index_path, final String dst_index_prefix)
	{
		this(src_index, dst_index_path, dst_index_prefix, Integer.parseInt(System.getProperty(EliasFano.LOG2QUANTUM, "8")));
	}
	
	public BasicCompressor(final Index src_index, final String dst_index_path, final String dst_index_prefix, final int log2quantum)
	{
		this.dst_index_path = dst_index_path;
		this.dst_index_prefix = dst_index_prefix;
		
		if (Index.existsIndex(dst_index_path, dst_index_prefix)) {
			LOGGER.error("Cannot compress index while an index already exists at " + dst_index_path + ", " + dst_index_prefix);
			this.src_index = null;
			this.num_docs = 0;
			return;
		}		
		this.src_index = src_index;		
		this.num_docs = src_index.getCollectionStatistics().getNumberOfDocuments();
		
		this.LOG2QUANTUM = log2quantum;
	}
	
	@SuppressWarnings("resource")
	@Override
	public void compress(final TermPartition terms) throws IOException
	{
		final int begin_term_id = terms.begin;
		final int end_term_id = terms.end;
		
		if (begin_term_id >= end_term_id || begin_term_id < 0 || end_term_id > src_index.getCollectionStatistics().getNumberOfUniqueTerms()) {
			LOGGER.error("Something wrong with termids, begin = " + begin_term_id + ", end = " + end_term_id);
			return;
		}

		// opening src index lexicon iterator and moving to the begin termid
		Iterator<Entry<String, LexiconEntry>> lex_iter = src_index.getLexicon().iterator();
		Entry<String, LexiconEntry> lee = null;
		while (lex_iter.hasNext()) {
			lee = lex_iter.next();
			if (lee.getValue().getTermId() == begin_term_id)
				break;
		}

		// writers
		LexiconOutputStream<String> los    = new FSOMapFileLexiconOutputStream(         dst_index_path + File.separator + terms.prefix() + ".lexicon" + FSOrderedMapFile.USUAL_EXTENSION, new FixedSizeTextFactory(IndexUtil.DEFAULT_MAX_TERM_LENGTH));
		LongWordBitWriter           docids = new LongWordBitWriter(new FileOutputStream(dst_index_path + File.separator + terms.prefix() + EliasFano.DOCID_EXTENSION).getChannel(), ByteOrder.nativeOrder());
		LongWordBitWriter           freqs  = new LongWordBitWriter(new FileOutputStream(dst_index_path + File.separator + terms.prefix() + EliasFano.FREQ_EXTENSION).getChannel(), ByteOrder.nativeOrder());
		
		// The sequence encoder to generate posting lists (docids)
		SequenceEncoder docidsAccumulator = new SequenceEncoder( DEFAULT_CACHE_SIZE, LOG2QUANTUM );
		// The sequence encoder to generate posting lists (freqs)
		SequenceEncoder freqsAccumulator = new SequenceEncoder( DEFAULT_CACHE_SIZE, LOG2QUANTUM );
				
		long docidsOffset = 0;
		long freqsOffset = 0;
		
		LexiconEntry le = null;
		IterablePosting p = null;
		
		int local_termid = 0;
		
		while (!stop(lee, end_term_id)) {
			le = lee.getValue();
			p = src_index.getInvertedIndex().getPostings((BitIndexPointer)lee.getValue());
			
			los.writeNextEntry(lee.getKey(), new EFLexiconEntry(local_termid, le.getDocumentFrequency(), le.getFrequency(), le.getMaxFrequencyInDocuments(), docidsOffset, freqsOffset));

			docidsAccumulator.init( le.getDocumentFrequency(), num_docs, false, true, LOG2QUANTUM );
			freqsAccumulator.init(  le.getDocumentFrequency(), le.getFrequency(), true, false, LOG2QUANTUM );
			
			long lastDocid = 0;
			while (p.next() != IterablePosting.END_OF_LIST) {
				docidsAccumulator.add( p.getId() - lastDocid );
				lastDocid = p.getId();
				freqsAccumulator.add(p.getFrequency());
			}
						
			docidsOffset += docidsAccumulator.dump(docids);		
			freqsOffset  += freqsAccumulator.dump(freqs);
			local_termid += 1;
			p.close();
			
			lee = lex_iter.hasNext() ? lex_iter.next() : null;
		} 
				
		docidsAccumulator.close();
		docids.close();
		freqsAccumulator.close();
		freqs.close();
		los.close();
	}
}