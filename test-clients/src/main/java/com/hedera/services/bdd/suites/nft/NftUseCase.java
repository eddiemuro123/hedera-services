package com.hedera.services.bdd.suites.nft;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class NftUseCase {
	enum Usage { COLLECTING, TRADING }

	private AtomicInteger nextXchange = new AtomicInteger(0);

	private final int users;
	private final int serialNos;
	private final int frequency;
	private final String useCase;
	private final boolean swapHbars;
	private final boolean queryBalances;

	private List<NftXchange> xchanges = new ArrayList<>();

	public NftUseCase(int users, int serialNos, int frequency, String useCase, boolean swapHbars, boolean queryBalances) {
		this.users = users;
		this.serialNos = serialNos;
		this.frequency = frequency;
		this.useCase = useCase;
		this.swapHbars = swapHbars;
		this.queryBalances = queryBalances;
	}

	public int getUsers() {
		return users;
	}

	public List<HapiSpecOperation> initializers(AtomicInteger nftTypeIds) {
		List<HapiSpecOperation> init = new ArrayList<>();
		for (int i = 0; i < frequency; i++) {
			var xchange = new NftXchange(
					users,
					nftTypeIds.getAndIncrement(),
					serialNos,
					useCase,
					swapHbars,
					queryBalances);
			init.add(UtilVerbs.withOpContext((spec, opLog) -> {
				opLog.info("Initializing {}...", xchange.getNftType());
			}));
			init.addAll(xchange.initializers());
			xchanges.add(xchange);
		}
		return init;
	}

	public HapiSpecOperation nextOp() {
		return xchanges
				.get(nextXchange.getAndUpdate(i -> (i + 1) % xchanges.size()))
				.nextOp();
	}

	public List<NftXchange> getXchanges() {
		return xchanges;
	}
}
