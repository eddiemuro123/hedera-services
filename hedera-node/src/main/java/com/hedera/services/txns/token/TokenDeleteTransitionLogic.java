package com.hedera.services.txns.token;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.context.TransactionContext;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.TransitionLogic;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Provides the state transition for token deletion.
 *
 * @author Michael Tinker
 */
public class TokenDeleteTransitionLogic implements TransitionLogic {
  private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;

  private final TransactionContext txnCtx;
  private final TypedTokenStore tokenStore;

  public TokenDeleteTransitionLogic(
      final TransactionContext txnCtx, final TypedTokenStore tokenStore) {
    this.txnCtx = txnCtx;
    this.tokenStore = tokenStore;
  }

  @Override
  public void doStateTransition() {
    /* --- Translate from gRPC types --- */
    final var op = txnCtx.accessor().getTxn().getTokenDeletion();
    final var grpcTokenId = op.getToken();

    /* --- Convert to model id --- */
    final var targetTokenId = Id.fromGrpcToken(grpcTokenId);

    /* --- Load the model object --- */
    final var loadedToken = tokenStore.loadToken(targetTokenId);

    /* --- Do the business logic --- */
    loadedToken.delete();

    /* --- Persist the updated model --- */
    tokenStore.persistToken(loadedToken);
  }

  @Override
  public Predicate<TransactionBody> applicability() {
    return TransactionBody::hasTokenDeletion;
  }

  @Override
  public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
    return SEMANTIC_CHECK;
  }

  public ResponseCodeEnum validate(final TransactionBody txnBody) {
    final TokenDeleteTransactionBody op = txnBody.getTokenDeletion();

    if (!op.hasToken()) {
      return INVALID_TOKEN_ID;
    }

    return OK;
  }
}
