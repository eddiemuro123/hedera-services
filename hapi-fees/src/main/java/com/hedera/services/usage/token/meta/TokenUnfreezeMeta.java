package com.hedera.services.usage.token.meta;

/**
 *  This is simply to get rid of code duplication with {@link TokenFreezeMeta} class.
 */
public class TokenUnfreezeMeta extends TokenFreezeMeta {
	public TokenUnfreezeMeta(final int bpt) {
		super(bpt);
	}
}
