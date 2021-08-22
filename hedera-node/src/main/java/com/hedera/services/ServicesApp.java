package com.hedera.services;

import com.hedera.services.context.ContextModule;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.PropertiesModule;
import com.hedera.services.fees.FeesModule;
import com.hedera.services.files.FilesModule;
import com.hedera.services.records.RecordsModule;
import com.hedera.services.sigs.SigsModule;
import com.hedera.services.state.StateModule;
import com.hedera.services.stats.StatsModule;
import com.hedera.services.store.StoresModule;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.throttling.ThrottlingModule;
import com.hedera.services.txns.LogicModule;
import com.swirlds.common.Platform;
import dagger.BindsInstance;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = {
		FeesModule.class,
		SigsModule.class,
		StatsModule.class,
		StateModule.class,
		FilesModule.class,
		LogicModule.class,
		StoresModule.class,
		ContextModule.class,
		RecordsModule.class,
		PropertiesModule.class,
		ThrottlingModule.class
})
public interface ServicesApp {
	TokenStore tokenStore();
	NodeLocalProperties nodeLocalProperties();
	GlobalDynamicProperties globalDynamicProperties();

	@Component.Builder
	interface Builder {
		@BindsInstance
		Builder platform(Platform platform);
		@BindsInstance
		Builder selfId(long selfId);
		@BindsInstance
		Builder initialState(ServicesState initialState);

		ServicesApp build();
	}
}
