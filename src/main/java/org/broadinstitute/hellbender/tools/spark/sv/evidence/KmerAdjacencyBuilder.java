package org.broadinstitute.hellbender.tools.spark.sv.evidence;

import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.spark.sv.utils.*;
import org.broadinstitute.hellbender.tools.spark.sv.utils.SVFastqUtils.FastqRead;
import org.broadinstitute.hellbender.tools.spark.utils.HopscotchSet;
import org.broadinstitute.hellbender.utils.BaseUtils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@BetaFeature
@CommandLineProgramProperties(
        oneLineSummary = "(Internal) junk", summary = "complete crap",
        programGroup = StructuralVariantDiscoveryProgramGroup.class)
public class KmerAdjacencyBuilder extends CommandLineProgram {
    private static final long serialVersionUID = 1L;

    @Argument(doc = "input fastq",
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME)
    private String fastqFile;

    @Argument(doc = "graph output",
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    private String graphOutput;

    @Argument(doc = "kmer size", fullName = "kSize", optional = true)
    private int kSize = 39;

    @Argument(doc = "minimum quality", fullName = "minQ", optional = true)
    private int minQ = 7;

    @Argument(doc = "minimum reliable kmer count", fullName = "minKCount", optional = true)
    private int minKCount = 4;

    @Override protected Object doWork() {
        final List<FastqRead> reads = SVFastqUtils.readFastqFile(fastqFile);
        if ( reads.size()%2 != 0 ) {
            throw new UserException("FASTQ input must be interleaved pairs, but there are an odd number of reads.");
        }

        final Map<SVKmerLong, Integer> contigEnds = new HashMap<>();
        final List<Contig> contigs =
                buildContigs(buildAdjacenciesSet(countKmers(reads, kSize, minQ, minKCount), kSize), kSize-2, contigEnds);

        final List<List<SVInterval>> readPaths = pathReadPairs(reads, contigs, kSize-2);

        // dump contigs
        dumpGFA( contigs, contigEnds, kSize - 2, graphOutput + ".gfa" );
        dumpFASTA( contigs, graphOutput + ".fasta" );
        dumpDOT( contigs, contigEnds, kSize - 2, graphOutput + ".dot" );

        return null;
    }

    private static List<List<SVInterval>> pathReadPairs( final List<FastqRead> reads, final List<Contig> contigs, final int kSize2 ) {
        final int nKmers = contigs.stream().mapToInt(tig -> tig.getSequence().length()-kSize2+1).sum();
        final Map<SVKmer, SVInterval> contigKmerMap = new HashMap<>(SVUtils.hashMapCapacity(nKmers));
        final int nContigs = contigs.size();
        for ( int contigId = 0; contigId != nContigs; ++contigId ) {
            int contigOffset = 0;
            final Iterator<SVKmer> contigItr =
                    new SVKmerizer(contigs.get(contigId).getSequence(), kSize2, new SVKmerLong());
            while ( contigItr.hasNext() ) {
                contigKmerMap.put(contigItr.next(), new SVInterval(contigId, contigOffset, contigOffset + kSize2));
                contigOffset += 1;
            }
        }

        final int nReads = reads.size();
        final List<List<SVInterval>> readPaths = new ArrayList<>(nReads);
        for ( int readId = 0; readId+1 < nReads; readId += 2 ) {
            final StringBuilder sb = new StringBuilder();
            sb.append("Pair ").append(readId / 2).append(": ");
            readPaths.add(processRead(sb, reads.get(readId).getBases(), kSize2, contigKmerMap, contigs));
            sb.append(" | ");
            readPaths.add(processRead(sb, BaseUtils.simpleReverseComplement(reads.get(readId+1).getBases()),
                            kSize2, contigKmerMap, contigs));
            System.out.println(sb);
        }
        return readPaths;
    }

    private static List<SVInterval> processRead( final StringBuilder sb, final byte[] sequence, final int kSize2,
                                     final Map<SVKmer, SVInterval> contigKmerMap, final List<Contig> contigs ) {
        final List<SVInterval> readPath = new ArrayList<>();
        SVInterval currentSpan = null;
        int missCount = 0;
        final Iterator<SVKmer> readItr =
                new NInsensitiveKmerizer(sequence, kSize2, new SVKmerLong());
        while ( readItr.hasNext() ) {
            final SVKmer kmer = readItr.next();
            SVInterval interval = contigKmerMap.get(kmer);
            if ( interval == null ) {
                SVInterval rcInterval = contigKmerMap.get(kmer.reverseComplement(kSize2));
                if ( rcInterval != null ) {
                    final int tigLen = contigs.get(rcInterval.getContig()).getSequence().length();
                    interval = new SVInterval(~rcInterval.getContig(),
                            tigLen - rcInterval.getEnd(),
                            tigLen - rcInterval.getStart());
                }
            }
            if ( interval == null ) {
                if ( currentSpan != null ) {
                    readPath.add(currentSpan);
                    appendInterval(sb, currentSpan, contigs);
                    currentSpan = null;
                }
                missCount += 1;
            } else if ( currentSpan == null ) {
                if ( missCount > 0 ) {
                    readPath.add(new SVInterval(-1, 0, missCount));
                    sb.append(missCount).append("X ");
                    missCount = 0;
                }
                currentSpan = interval;
            } else if ( interval.getContig() == currentSpan.getContig() &&
                    interval.getEnd() == currentSpan.getEnd() + 1 ) {
                currentSpan = interval.join(currentSpan);
            } else {
                readPath.add(currentSpan);
                appendInterval(sb, currentSpan, contigs);
                currentSpan = interval;
            }
        }
        if ( missCount > 0 ) {
            readPath.add(new SVInterval(-1, 0, missCount));
            sb.append(missCount).append("X ");
        }
        if ( currentSpan != null ) {
            readPath.add(currentSpan);
            appendInterval(sb, currentSpan, contigs);
        }
        return readPath;
    }

    private static void appendInterval( final StringBuilder sb, final SVInterval interval, final List<Contig> contigs ) {
        final int contigId = interval.getContig();
        if ( contigId < 0 ) sb.append('~').append(~contigId);
        else sb.append(contigId);
        sb.append(":").append(interval.getStart()).append('-').append(interval.getEnd()-1).append('/');
        sb.append(contigs.get(contigId < 0 ? ~contigId : contigId).getSequence().length()-1).append(' ');
    }

    private static List<Contig> buildContigs( final HopscotchSet<SVKmerAdjacencies> kmerAdjacenciesSet,
                                              final int kSize2,
                                              final Map<SVKmerLong, Integer> contigEnds ) {
        // build contigs
        final List<Contig> contigs = new ArrayList<>();
        kmerAdjacenciesSet.forEach( adj -> {
            if ( !contigEnds.containsKey(adj) && !contigEnds.containsKey(adj.reverseComplement(kSize2)) ) {
                Contig contig = null;
                if ( isContigStart(adj, kmerAdjacenciesSet, kSize2) ) {
                    contig = buildContig(adj, kSize2, kmerAdjacenciesSet);
                } else if ( isContigEnd(adj, kmerAdjacenciesSet, kSize2) ) {
                    contig = buildContig(adj.reverseComplement(kSize2), kSize2, kmerAdjacenciesSet);
                }
                if ( contig != null ) {
                    final int contigId = contigs.size();
                    contigs.add(contig);
                    contigEnds.put(contig.getFirst(), contigId);
                    contigEnds.put(contig.getLast().reverseComplement(kSize2), ~contigId);
                }
            }
        });

        return contigs;
    }

    private static HopscotchSet<SVKmerAdjacencies> buildAdjacenciesSet( final Set<SVKmerLong> goodKmers,
                                                                        final int kSize ) {
        // build 61-mer adjacency map from 63-mers (assuming default kmer size)
        final int kSize2 = kSize - 2;
        final HopscotchSet<SVKmerAdjacencies> kmerAdjacenciesSet =
                new HopscotchSet<>(3*SVUtils.hashMapCapacity(goodKmers.size()));
        goodKmers.forEach(kmer -> {
            final SVKmerAdjacencies newAdj =
                    new SVKmerAdjacencies(kmer.removeFirstAndLastBase(kSize), kmer.firstBase(kSize), kmer.lastBase());
            final SVKmerAdjacencies oldAdj = kmerAdjacenciesSet.find(newAdj);
            if ( oldAdj == null ) kmerAdjacenciesSet.add(newAdj);
            else oldAdj.mergeAdjacencies(newAdj);

            final SVKmerAdjacencies newPrevAdj =
                    (SVKmerAdjacencies)new SVKmerAdjacencies(newAdj.predecessor(kmer.firstBase(kSize), kSize2),
                            null,
                            newAdj.lastBase()).canonical(kSize2);
            final SVKmerAdjacencies oldPrevAdj = kmerAdjacenciesSet.find(newPrevAdj);
            if ( oldPrevAdj == null ) kmerAdjacenciesSet.add(newPrevAdj);
            else oldPrevAdj.mergeAdjacencies(newPrevAdj);

            final SVKmerAdjacencies newNextAdj =
                    (SVKmerAdjacencies)new SVKmerAdjacencies(newAdj.successor(kmer.lastBase(), kSize2),
                            newAdj.firstBase(kSize2), null).canonical(kSize2);
            final SVKmerAdjacencies oldNextAdj = kmerAdjacenciesSet.find(newNextAdj);
            if ( oldNextAdj == null ) kmerAdjacenciesSet.add(newNextAdj);
            else oldNextAdj.mergeAdjacencies(newNextAdj);
        });

        return kmerAdjacenciesSet;
    }

    private static Set<SVKmerLong> countKmers( final List<FastqRead> reads,
                                               final int kSize,
                                               final int minQ,
                                               final int minKCount ) {
        // kmerize each read, counting the observations of each kmer.
        // ignore kmers that contain a call with a quality less than minQ
        final int nKmers = reads.stream().mapToInt(read -> Math.min(0, read.getBases().length - kSize + 1)).sum();
        final Map<SVKmerLong, Integer> kmerCounts = new HashMap<>(SVUtils.hashMapCapacity(nKmers));
        for ( final FastqRead read : reads ) {
            SVKmerizer.canonicalStream(maskedSequence(read, minQ), kSize, new SVKmerLong(kSize))
                    .forEach(kmer -> kmerCounts.merge((SVKmerLong)kmer, 1, Integer::sum));
        }

        // create a histogram of kmer counts to determine first local minimum
        final Map<Integer, Integer> kmerCountHistogram = new TreeMap<>();
        kmerCounts.values().forEach( count -> kmerCountHistogram.merge(count, 1, Integer::sum));
        final int minCount = minKCount <= 0 ? findMinKCount(kmerCountHistogram) : minKCount;

        // dump histogram of kmer counts
        System.out.println("minKCount=" + minCount);
        kmerCountHistogram.forEach( (count, countCount) -> System.out.println(count + "\t" + countCount));

        // ignore kmers that appear less than minKCount times
        kmerCounts.entrySet().removeIf(entry -> entry.getValue() < minCount);

        return kmerCounts.keySet();
    }

    private static byte[] maskedSequence(final FastqRead read, final int minQ) {
        final byte[] quals = read.getQuals();
        final byte[] calls = Arrays.copyOf(read.getBases(), quals.length);
        for ( int idx = 0; idx != quals.length; ++idx ) {
            if ( quals[idx] < minQ ) calls[idx] = 'N';
        }
        return calls;
    }

    // first the 1st local minimum in the histogram of kmer counts
    private static int findMinKCount( final Map<Integer, Integer> kmerCountHistogram ) {
        Map.Entry<Integer, Integer> troughEntry = null;
        for ( final Map.Entry<Integer, Integer> entry : kmerCountHistogram.entrySet() ) {
            if ( troughEntry == null ) troughEntry = entry;
            else {
                int cmpVal = entry.getValue().compareTo(troughEntry.getValue());
                if ( cmpVal < 0 ) troughEntry = entry;
                else if ( cmpVal > 0 ) break;
            }
        }
        return troughEntry == null ? 3 : troughEntry.getKey();
    }

    private static void dumpDOT( final List<Contig> contigs,
                                 final Map<SVKmerLong, Integer> contigEnds,
                                 final int kSize,
                                 final String fileName ) {
        try ( final BufferedWriter writer = new BufferedWriter(new FileWriter(fileName)) ) {
            writer.write("digraph {\n");
            final int nContigs = contigs.size();
            for ( int contigId = 0; contigId != nContigs; ++contigId ) {
                final Contig contig = contigs.get(contigId);
                final double width = contig.getSequence().length() / 100.;
                writer.write("tig" + contigId + " [width=" + width + "]\n");
                writer.write("tig" + contigId + "RC [width=" + width + "]\n");
            }
            for ( int contigId = 0; contigId != nContigs; ++contigId ) {
                final Contig contig = contigs.get(contigId);
                for ( final int strandId : contig.getFirst().getPredecessorContigs(contigEnds, kSize) ) {
                    final int targetId = strandId < 0 ? ~strandId : strandId;
                    final boolean targetRC = strandId < 0;
                    if ( targetId >= contigId ) {
                        writer.write("tig" + contigId + "RC -> tig" + targetId + (targetRC ? "RC\n" : "\n"));
                        if ( targetId != contigId ) {
                            writer.write("tig" + targetId + (targetRC ? "" : "RC") + " -> tig" + contigId + "\n");
                        }
                    }
                }
                for ( final int strandId : contig.getLast().getSuccessorContigs(contigEnds, kSize) ) {
                    final int targetId = strandId < 0 ? ~strandId : strandId;
                    final boolean targetRC = strandId < 0;
                    if ( targetId >= contigId ) {
                        writer.write("tig" + contigId + " -> tig" + targetId + (targetRC ? "RC\n" : "\n"));
                        if ( targetId != contigId ) {
                            writer.write("tig" + targetId + (targetRC ? "" : "RC") + " -> tig" + contigId + "RC\n");
                        }
                    }
                }
            }
            writer.write("}\n");
        } catch ( final IOException ioe ) {
            throw new GATKException("Failed to write assembly DOT file.", ioe);
        }
    }

    private static void dumpFASTA( final List<Contig> contigs, final String fileName ) {
        try ( final BufferedWriter writer = new BufferedWriter(new FileWriter(fileName)) ) {
            final int nContigs = contigs.size();
            for ( int contigId = 0; contigId != nContigs; ++contigId ) {
                writer.write(">" + contigId + "\n");
                writer.write(contigs.get(contigId).getSequence() + "\n");
            }
        } catch ( final IOException ioe ) {
            throw new GATKException("Failed to write assembly FASTA file.", ioe);
        }
    }

    private static void dumpGFA( final List<Contig> contigs,
                                 final Map<SVKmerLong, Integer> contigEnds,
                                 final int kSize,
                                 final String fileName ) {
        try ( final BufferedWriter writer = new BufferedWriter(new FileWriter(fileName)) ) {
            final int nContigs = contigs.size();
            for ( int contigId = 0; contigId != nContigs; ++contigId ) {
                final Contig contig = contigs.get(contigId);
                final int seqLen = contig.getSequence().length();
                writer.write("S\ttig" + contigId + "\t" + contig.getSequence() + "\tLN:i:" + seqLen + "\n");
                for ( final int strandId : contig.getFirst().getPredecessorContigs(contigEnds, kSize) ) {
                    final int targetId = strandId < 0 ? ~strandId : strandId;
                    if ( targetId > contigId ) { // intentionally different than analogous comparison a few lines below
                        final String dir = strandId < 0 ? "-" : "+";
                        writer.write("L\ttig" + contigId + "\t-\ttig" + targetId + "\t" + dir + "\n");
                    }
                }
                for ( final int strandId : contig.getLast().getSuccessorContigs(contigEnds, kSize) ) {
                    final int targetId = strandId < 0 ? ~strandId : strandId;
                    if ( targetId >= contigId ) {
                        final String dir = strandId < 0 ? "-" : "+";
                        writer.write("L\ttig" + contigId + "\t+\ttig" + targetId + "\t" + dir + "\n");
                    }
                }
            }
        } catch ( final IOException ioe ) {
            throw new GATKException("Failed to write assembly FASTA file.", ioe);
        }
    }

    private static SVKmerAdjacencies strandSensitiveLookup( final SVKmerLong kmer,
                                                            final int kSize,
                                                            final HopscotchSet<SVKmerAdjacencies> kmerAdjacenciesSet ) {
        final SVKmerLong canonicalKmer = kmer.canonical(kSize);
        final SVKmerAdjacencies kmerAdjacencies =
                kmerAdjacenciesSet.find(canonicalKmer);
        if ( kmerAdjacencies == null ) {
            throw new GATKException("can't find expected kmer in adjacencies set");
        }
        return kmer.equals(canonicalKmer) ? kmerAdjacencies : kmerAdjacencies.reverseComplement(kSize);
    }

    private static boolean isContigStart( final SVKmerAdjacencies kmerAdjacencies,
                                          final HopscotchSet<SVKmerAdjacencies> kmerAdjacenciesSet,
                                          final int kSize ) {
        final SVKmerLong predecessorKmer = kmerAdjacencies.getSolePredecessor(kSize);
        if ( predecessorKmer == null ) return true;
        final SVKmerAdjacencies predecessorAdjacencies =
                strandSensitiveLookup(predecessorKmer, kSize, kmerAdjacenciesSet);
        return predecessorAdjacencies.successorCount() > 1;
    }

    private static boolean isContigEnd( final SVKmerAdjacencies kmerAdjacencies,
                                        final HopscotchSet<SVKmerAdjacencies> kmerAdjacenciesSet,
                                        final int kSize ) {
        final SVKmerLong successorKmer = kmerAdjacencies.getSoleSuccessor(kSize);
        if ( successorKmer == null ) return true;
        final SVKmerAdjacencies successorAdjacencies =
                strandSensitiveLookup(successorKmer, kSize, kmerAdjacenciesSet);
        return successorAdjacencies.predecessorCount() > 1;
    }

    private static Contig buildContig( final SVKmerAdjacencies kmerAdjacencies, final int kSize,
                                       final HopscotchSet<SVKmerAdjacencies> kmerAdjacenciesSet ) {
        final StringBuilder contigSequence = new StringBuilder(kmerAdjacencies.toString(kSize));
        SVKmerAdjacencies currentAdjacencies = kmerAdjacencies;
        SVKmerLong successorKmer;
        while ( (successorKmer = currentAdjacencies.getSoleSuccessor(kSize)) != null ) {
            final SVKmerAdjacencies successorAdjacencies =
                    strandSensitiveLookup(successorKmer, kSize, kmerAdjacenciesSet);
            if ( successorAdjacencies.predecessorCount() > 1 ) break;
            contigSequence.append(successorAdjacencies.lastBase().name());
            currentAdjacencies = successorAdjacencies;
        }
        return new Contig(contigSequence.toString(), kmerAdjacencies, currentAdjacencies);
    }

    private static final class Contig {
        private final String sequence;
        private final SVKmerAdjacencies first;
        private final SVKmerAdjacencies last;

        public Contig( final String sequence, final SVKmerAdjacencies first, final SVKmerAdjacencies last ) {
            this.sequence = sequence;
            this.first = first;
            this.last = last;
        }

        public String getSequence() { return sequence; }
        public SVKmerAdjacencies getFirst() { return first; }
        public SVKmerAdjacencies getLast() { return last; }
    }

    private static final class NInsensitiveKmerizer extends SVKmerizer {
        public NInsensitiveKmerizer( final byte[] seq, final int kSize, final SVKmer exemplar ) {
            super(seq, kSize, exemplar);
        }

        protected SVKmer nextKmer( SVKmer tmpKmer, int validBaseCount ) {
            final int len = seq.length();
            while ( idx < len ) {
                switch ( seq.charAt(idx) ) {
                    case 'a': case 'A': tmpKmer = tmpKmer.successor(SVKmer.Base.A, kSize); break;
                    case 'c': case 'C': tmpKmer = tmpKmer.successor(SVKmer.Base.C, kSize); break;
                    case 'g': case 'G': tmpKmer = tmpKmer.successor(SVKmer.Base.G, kSize); break;
                    default: tmpKmer = tmpKmer.successor(SVKmer.Base.T, kSize); break;
                }
                idx += 1;

                if ( ++validBaseCount == kSize ) return tmpKmer;
            }
            return null;
        }
    }

    private static final class ContigExtension {
        private List<byte[]> predecessors;
        private List<byte[]> successors;

        public ContigExtension() {
            predecessors = new ArrayList<>();
            successors = new ArrayList<>();
        }

        public void addPredecessor( final byte[] calls ) {
            predecessors.add(calls);
        }

        public void addSuccessor( final byte[] calls ) {
            successors.add(calls);
        }
    }
}