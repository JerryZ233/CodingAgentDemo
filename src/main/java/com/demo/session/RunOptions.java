package com.demo.session;
/** Caller promises to service approval requests until completion or cancellation. */
public record RunOptions(boolean approvalChannelAvailable) {
 public static RunOptions defaults(){return new RunOptions(false);}
 public static RunOptions interactive(){return new RunOptions(true);}
}
