package com.demo.llm;

/** Per-call metadata; never mutable client/thread state and never sent as a provider parameter. */
public enum RequestPurpose { AGENT, COMPACTION, CONSOLIDATION }
