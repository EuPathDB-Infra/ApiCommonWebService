package org.apidb.apicomplexa.wsfplugin.motifsearch.poc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class MotifHorizonMatcherPocDriver {

    public static void main(String[] args) throws Exception {
        final String motif8VeryBeginning = "ACCCATGC";
        attemptTileMatch(Pattern.compile(motif8VeryBeginning), 20);
        final String motifVeryEnd = "TGAGCGCAGGTGCTGATCAAT";
        attemptTileMatch(Pattern.compile(motifVeryEnd), 20);
    }


    private static void attemptTileMatch(Pattern pattern, int contextLength) throws Exception {
        try (final SequenceReaderProvider sequenceFileStreamer = new SequenceReaderProvider(new File("tst/data-files/GenomeDoubleStrandCollapsed"))) {
            do {
                final Optional<SequenceReaderProvider.FastaReader> input = sequenceFileStreamer.nextSequence();
                if (input.isEmpty()) {
                    System.out.println("No more inputs.");
                    return;
                }
                System.out.println("Reading input.");
                List<MatchWithContext> matches = new ArrayList<>();
                BufferedDnaMotifFinder.match(input.get(), pattern, contextLength, matches::add, 8192);
                matches.forEach(motifMatch -> System.out.println("Found match:" +
                        " trailing=" + motifMatch.getTrailingContext() +
                        " leading=" + motifMatch.getLeadingContext() +
                        " group=" + motifMatch.getMatch() +
                        " organism=" + input.get().getCurrentOrganism() +
                        " sequenceId=" + input.get().getCurrentSequenceId() +
                        " strand=" + input.get().getCurrentStrand()));
                if (matches.isEmpty()) {
                    System.out.println("Mo match found for pattern: " + pattern);
                }
            } while (true);
        }
    }
}
