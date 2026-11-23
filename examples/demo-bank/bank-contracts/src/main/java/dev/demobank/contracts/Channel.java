package dev.demobank.contracts;

/** Channel a payment was made from. */
public enum Channel {
  WEB,
  MOBILE;

  public static final Channel DEFAULT = WEB;

  /** Returns the given channel, or {@link #DEFAULT} when it is null. */
  public static Channel orDefault(Channel channel) {
    return channel != null ? channel : DEFAULT;
  }
}
