package be.nabu.libs.types.uml;

import be.nabu.libs.types.properties.SimpleProperty;

public class ReferencedTypeProperty extends SimpleProperty<String> {

	private static ReferencedTypeProperty instance = new ReferencedTypeProperty();
	
	public static ReferencedTypeProperty getInstance() {
		return instance;
	}
	
	public ReferencedTypeProperty() {
		super(String.class);
	}

	@Override
	public String getName() {
		return "referencedType";
	}

}
