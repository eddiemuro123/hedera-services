package com.hedera.services.bdd.spec.persistence;

import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

public class SkipNullRepresenter extends Representer {
	@Override
	protected NodeTuple representJavaBeanProperty(
			Object javaBean,
			Property property,
			Object propertyValue,
			Tag customTag
	) {
		if (propertyValue == null) {
			return null;
		} else {
			return super.representJavaBeanProperty(javaBean, property, propertyValue, customTag);
		}
	}
}
