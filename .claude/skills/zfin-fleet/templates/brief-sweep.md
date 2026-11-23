# {{NNN}}: {{TICKET}}. Review follow-up: show me evidence across ALL affected cases

The diff looks right on the examples. Before it goes to the developer, I want the whole population.

1. Run the change over every affected record, package or page. Write `.sidecar/data/all_results.tsv` with the input, the output and a count.
2. Write `.sidecar/data/anomalies.txt`, bucketed by suspicious shape, with a count and 5 examples per bucket:
   a. <e.g. outputs that look under-processed>
   b. <e.g. outputs that look over-processed>
   c. <e.g. inputs that look processable but were left alone>
   d. <e.g. malformed outputs: empty, stray separators, wrong case>
3. Fix the code only for buckets that show genuine, general bugs, and add a test per fix. List one-off data problems; don't special-case them.

Amend the commit if you change code, and report in outbox/{{NNN}}.md with the counts per bucket, examples, and what changed.
