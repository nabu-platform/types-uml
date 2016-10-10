package be.nabu.libs.types.uml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.converter.api.Converter;
import be.nabu.libs.property.PropertyFactory;
import be.nabu.libs.property.ValueUtils;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeRegistryImpl;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.DefinedType;
import be.nabu.libs.types.api.DefinedTypeRegistry;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.ModifiableType;
import be.nabu.libs.types.api.ModifiableComplexType;
import be.nabu.libs.types.api.ModifiableTypeRegistry;
import be.nabu.libs.types.api.SimpleType;
import be.nabu.libs.types.api.SimpleTypeWrapper;
import be.nabu.libs.types.api.Type;
import be.nabu.libs.types.base.ComplexElementImpl;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.properties.CollectionNameProperty;
import be.nabu.libs.types.properties.CommentProperty;
import be.nabu.libs.types.properties.ForeignKeyProperty;
import be.nabu.libs.types.properties.FormatProperty;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.libs.types.properties.TimezoneProperty;
import be.nabu.libs.types.structure.DefinedStructure;
import be.nabu.libs.types.structure.Structure;
import be.nabu.libs.types.structure.SuperTypeProperty;
import be.nabu.libs.types.xml.ResourceResolver;
import be.nabu.libs.types.xml.URLResourceResolver;
import be.nabu.libs.types.xml.XMLSchema;
import be.nabu.utils.xml.BaseNamespaceResolver;
import be.nabu.utils.xml.XMLUtils;
import be.nabu.utils.xml.XPath;

public class UMLRegistry implements DefinedTypeRegistry {

	private static BaseNamespaceResolver resolver;
	
	static {
		resolver = new BaseNamespaceResolver();
		resolver.registerPrefix("uml", "org.omg.xmi.namespace.UML");
	}
	
	private String createdField = "dbCreatedUtc", modifiedField = "dbModifiedUtc";
	private String id;
	private ModifiableTypeRegistry registry = new TypeRegistryImpl();
	private Map<String, Element<?>> children = new HashMap<String, Element<?>>();
	private Map<String, Type> dataTypes = new HashMap<String, Type>();
	private Map<String, String> dataTypeNames = new HashMap<String, String>();
	private Map<String, Property<?>> properties = new HashMap<String, Property<?>>();
	private SimpleTypeWrapper wrapper = SimpleTypeWrapperFactory.getInstance().getWrapper();
	private Converter converter = ConverterFactory.getInstance().getConverter();
	private Logger logger = LoggerFactory.getLogger(getClass());
	private ResourceResolver resourceResolver;
	private List<URI> loadedUris = new ArrayList<URI>();
	private boolean generateFlatDocuments = true;
	private boolean addDatabaseFields = true;
	private boolean uuids = true;
	private boolean useExtensions = false;
	private boolean generateCollectionNames = false;
	// the xmi id for the local "useExtensions" property
	private String localUseExtensions, localCollectionName, localIgnoreExtensions;
	private Map<String, Boolean> localUseExtensionsMap = new HashMap<String, Boolean>();
	private Map<String, Boolean> localIgnoreExtensionsMap = new HashMap<String, Boolean>();
	// the xmi id for a "documentation" tag
	private String documentationId;
	
	// this was old behavior due to a bug in modeling
	private boolean inverseParentChildRelationship;
	
	public UMLRegistry(String id) {
		this.id = id;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void load(Document...documents) {
		List<org.w3c.dom.Element> models = new ArrayList<org.w3c.dom.Element>();
		for (Document document : documents) {
			models.addAll(new XPath("//uml:Model").setNamespaceContext(resolver).query(document).asElementList());
		}
		List<org.w3c.dom.Element> packages = new ArrayList<org.w3c.dom.Element>();
		for (org.w3c.dom.Element model : models) {
			packages.addAll(new XPath("uml:Namespace.ownedElement/uml:Package").setNamespaceContext(resolver).query(model).asElementList());
		}
		models.addAll(packages);
		// we don't know which order the models should load one another (might be interdependencies)
		// so just load elements in the order of least likely conflict
		// first load all the tags for all the models
		for (org.w3c.dom.Element model : models) {
			for (org.w3c.dom.Element tag : new XPath("uml:Namespace.ownedElement/uml:TagDefinition").setNamespaceContext(resolver).query(model).asElementList()) {
				if ("useExtensions".equals(tag.getAttribute("name"))) {
					localUseExtensions = tag.getAttribute("xmi.id");
				}
				else if ("collectionName".equals(tag.getAttribute("name"))) {
					localCollectionName = tag.getAttribute("xmi.id");
				}
				else if ("ignoreExtensions".equals(tag.getAttribute("name"))) {
					localIgnoreExtensions = tag.getAttribute("xmi.id");
				}
				else if ("documentation".equals(tag.getAttribute("name"))) {
					documentationId = tag.getAttribute("xmi.id");	
				}
				else {
					Property<?> property = PropertyFactory.getInstance().getProperty(tag.getAttribute("name"));
					if (property != null) {
						properties.put(tag.getAttribute("xmi.id"), property);
					}
					else {
						logger.warn("Unknown tag: " + tag.getAttribute("name"));
					}
				}
			}
		}
		// then we load all the data types
		for (org.w3c.dom.Element model : models) {
			for (org.w3c.dom.Element dataType : new XPath("uml:Namespace.ownedElement/uml:DataType").setNamespaceContext(resolver).query(model).asElementList()) {
				SimpleType<?> nativeSchemaType = XMLSchema.getNativeSchemaType(dataType.getAttribute("name"), wrapper);
				if (nativeSchemaType == null) {
					nativeSchemaType = SimpleTypeWrapperFactory.getInstance().getWrapper().getByName(dataType.getAttribute("name"));
				}
				if (nativeSchemaType == null) { 
					logger.warn("Unknown simple type: " + dataType.getAttribute("name"));
				}
				else {
					dataTypes.put(dataType.getAttribute("xmi.id"), nativeSchemaType);
					dataTypeNames.put(dataType.getAttribute("xmi.id"), dataType.getAttribute("name"));
				}
			}
		}
		// lastly we load all the actual classes
		for (org.w3c.dom.Element model : models) {
			// in argouml it is possible to fill in a namespace though it is unclear how you add one to the dropdown at this point
			// due to the apparent lack of XSD of the uml standard, it is hard to see how this would be in the XML
			// TODO: need to check how the namespace will appear in the XML, we currently assume an attribute
			String namespace = model.hasAttribute("namespace") ? model.getAttribute("namespace") : null;
			String name = model.hasAttribute("name") ? model.getAttribute("name") : null;
			if (namespace == null) {
				namespace = (id == null ? "" : id + ".") + name;
			}
			// load the properties
			// load the data types
			// load the classes
			DefinedSimpleType idType = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(uuids ? UUID.class : Long.class);
			for (org.w3c.dom.Element clazz : new XPath("uml:Namespace.ownedElement/uml:Class").setNamespaceContext(resolver).query(model).asElementList()) {
				DefinedStructure structure = new DefinedStructure();
				structure.setName(clazz.getAttribute("name"));
				structure.setId((id == null ? "" : id + ".") + name + "." + structure.getName());
				structure.setNamespace(namespace);
				if (addDatabaseFields) {
					DefinedSimpleType<Date> dateWrapper = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(Date.class);
					structure.add(new SimpleElementImpl("id", idType, structure));
					if (createdField != null) {
						structure.add(new SimpleElementImpl<Date>(createdField, dateWrapper, structure, new ValueImpl<TimeZone>(TimezoneProperty.getInstance(), TimeZone.getTimeZone("UTC"))));
					}
					if (modifiedField != null) {
						structure.add(new SimpleElementImpl<Date>(modifiedField, dateWrapper, structure, new ValueImpl<TimeZone>(TimezoneProperty.getInstance(), TimeZone.getTimeZone("UTC"))));
					}
				}
				dataTypes.put(clazz.getAttribute("xmi.id"), structure);
				registry.register(structure);
				boolean hasCollectionName = false;
				// you can set a tag on a class to have it use extensions
				for (org.w3c.dom.Element tag : new XPath("uml:ModelElement.taggedValue/uml:TaggedValue").setNamespaceContext(resolver).query(clazz).asElementList()) {
					String value = new XPath("uml:TaggedValue.dataValue").setNamespaceContext(resolver).query(tag).asString();
					String id = new XPath("uml:TaggedValue.type/uml:TagDefinition/@xmi.idref").setNamespaceContext(resolver).query(tag).asString();
					if (id == null || id.trim().isEmpty()) {
						String href = new XPath("uml:TaggedValue.type/uml:TagDefinition/@href").setNamespaceContext(resolver).query(tag).asString();
						if (href != null && !href.trim().isEmpty()) {
							id = href.replaceFirst("^.*#", "");
						}
					}
					if (localUseExtensions != null && localUseExtensions.equals(id)) {
						localUseExtensionsMap.put(clazz.getAttribute("xmi.id"), value.equals("true"));
					}
					else if (localIgnoreExtensions != null && localIgnoreExtensions.equals(id)) {
						localIgnoreExtensionsMap.put(clazz.getAttribute("xmi.id"), value.equals("true"));
					}
					else if (localCollectionName != null && localCollectionName.equals(id)) {
						structure.setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), value));
						hasCollectionName = true;
					}
				}
				if (!hasCollectionName && generateCollectionNames) {
					structure.setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), structure.getName() + "s"));
				}
			}
			// need to fill in attributes _after_ all classes are loaded, otherwise we can't resolve references
			for (org.w3c.dom.Element clazz : new XPath("uml:Namespace.ownedElement/uml:Class").setNamespaceContext(resolver).query(model).asElementList()) {
				Structure structure = (Structure) dataTypes.get(clazz.getAttribute("xmi.id"));
				for (org.w3c.dom.Element attribute : new XPath("uml:Classifier.feature/uml:Attribute").setNamespaceContext(resolver).query(clazz).asElementList()) {
					List<Value<?>> values = new ArrayList<Value<?>>();
					String attributeName = attribute.getAttribute("name");
					Type type = null;
					
					String minOccurs = new XPath("uml:StructuralFeature.multiplicity/uml:Multiplicity/uml:Multiplicity.range/uml:MultiplicityRange/@lower").setNamespaceContext(resolver).query(attribute).asString();
					if (minOccurs != null) {
						values.add(new ValueImpl<Integer>(MinOccursProperty.getInstance(), Integer.parseInt(minOccurs)));
					}
					String maxOccurs = new XPath("uml:StructuralFeature.multiplicity/uml:Multiplicity/uml:Multiplicity.range/uml:MultiplicityRange/@upper").setNamespaceContext(resolver).query(attribute).asString();
					if (maxOccurs != null) {
						values.add(new ValueImpl<Integer>(MaxOccursProperty.getInstance(), Integer.parseInt(maxOccurs.equals("-1") ? "0" : maxOccurs)));
					}
					// set the other properties
					NodeList taggedValues = new XPath("uml:ModelElement.taggedValue/uml:TaggedValue").setNamespaceContext(resolver).query(attribute).asNodeList();
					if (taggedValues != null) {
						for (int i = 0; i < taggedValues.getLength(); i++) {
							String value = new XPath("uml:TaggedValue.dataValue").setNamespaceContext(resolver).query(taggedValues.item(i)).asString();
							String id = new XPath("uml:TaggedValue.type/uml:TagDefinition/@xmi.idref").setNamespaceContext(resolver).query(taggedValues.item(i)).asString();
							if (id == null || id.trim().isEmpty()) {
								String href = new XPath("uml:TaggedValue.type/uml:TagDefinition/@href").setNamespaceContext(resolver).query(taggedValues.item(i)).asString();
								if (href != null && !href.trim().isEmpty()) {
									id = href.replaceFirst("^.*#", "");
								}
							}
							if (documentationId != null && documentationId.equals(id)) {
								values.add(new ValueImpl(CommentProperty.getInstance(), value));
							}
							else if (properties.containsKey(id) && value != null) {
								Object convertedValue = converter.convert(value, properties.get(id).getValueClass());
								values.add(new ValueImpl(properties.get(id), convertedValue));
							}
						}
					}
					// set the type
					String typeId = new XPath("uml:StructuralFeature.type/uml:DataType/@xmi.idref").setNamespaceContext(resolver).query(attribute).asString();
					// you can also reference a class instead of a simple data type
					if (typeId == null || typeId.trim().isEmpty()) {
						typeId = new XPath("uml:StructuralFeature.type/uml:Class/@xmi.idref").setNamespaceContext(resolver).query(attribute).asString();
					}
					String dataTypeName = null;
					if (typeId != null && dataTypes.containsKey(typeId)) {
						type = dataTypes.get(typeId);
						dataTypeName = dataTypeNames.get(typeId);
					}
					else {
						String referencedTypeId = new XPath("uml:StructuralFeature.type/uml:DataType/@href").setNamespaceContext(resolver).query(attribute).asString();
						if (referencedTypeId != null) {
							try {
								URI uri = new URI(referencedTypeId);
								// the fragment indicates the type
								if (uri.getFragment() != null) {
									// if we don't know the data type yet, do a best effort to load it
									// each uri should only be loaded (or tried) once
									if (!dataTypes.containsKey(uri.getFragment()) && !loadedUris.contains(uri)) {
										loadedUris.add(uri);
										InputStream resolvedData = getResourceResolver().resolve(uri);
										if (resolvedData != null) {
											try {
												Document document = XMLUtils.toDocument(resolvedData, true);
												load(document);
											}
											catch (SAXException e) {
												logger.error("Can not parse referenced scheme: " + uri, e);
											}
											catch (ParserConfigurationException e) {
												logger.error("Can not parse referenced scheme: " + uri, e);
											}
											finally {
												resolvedData.close();
											}
										}
									}
									type = dataTypes.get(uri.getFragment());
									dataTypeName = dataTypeNames.get(uri.getFragment());
								}
							}
							catch (URISyntaxException e) {
								logger.error("Can not resolve referenced type: " + referencedTypeId, e);
							}
							catch (IOException e) {
								logger.error("Can not resolve referenced type: " + referencedTypeId, e);
							}
						}
					}
					if (type == null) {
						type = wrapper.wrap(String.class);
					}
					if (attributeName != null && type != null) {
						Element<?> child;
						if (type instanceof ComplexType) {
							if (generateFlatDocuments) {
								if (type instanceof DefinedType) {
									values.add(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) type).getId() + ":id"));
								}
								child = new SimpleElementImpl(attributeName + "Id", idType, structure, values.toArray(new Value[values.size()]));
							}
							else {
								child = new ComplexElementImpl(attributeName, (ComplexType) type, structure, values.toArray(new Value[values.size()]));
							}
						}
						else {
							child = new SimpleElementImpl(attributeName, (SimpleType) type, structure, values.toArray(new Value[values.size()]));
							// if you are using a date that is not the default dateTime and you haven't explicitly set a format, inject one
							if (dataTypeName != null && !dataTypeName.equals("dateTime") && Date.class.equals(((SimpleType) type).getInstanceClass()) && ValueUtils.getValue(FormatProperty.getInstance(), child.getProperties()) == null) {
								child.setProperty(new ValueImpl<String>(FormatProperty.getInstance(), dataTypeName));
							}
						}
						structure.add(child);
						children.put(child.getName(), child);
					}
				}
			}
			// load any generalizations between them (extensions)
			for (org.w3c.dom.Element generalization : new XPath("uml:Namespace.ownedElement/uml:Generalization").setNamespaceContext(resolver).query(model).asElementList()) {
				String superClass = new XPath("uml:Generalization.parent/uml:Class/@xmi.idref").setNamespaceContext(resolver).query(generalization).asString();
				String childClass = new XPath("uml:Generalization.child/uml:Class/@xmi.idref").setNamespaceContext(resolver).query(generalization).asString();
				// simulate old behavior
				if (inverseParentChildRelationship) {
					String tmp = superClass;
					superClass = childClass;
					childClass = tmp;
				}
				if (superClass == null || childClass == null) {
					logger.error("Can not implement generalization from " + superClass + " to " + childClass);
					continue;
				}
				Type superType = dataTypes.get(superClass);
				Type childType = dataTypes.get(childClass);
				if (superType == null || childType == null) {
					logger.error("Can not resolve " + superClass + " or " + childClass + ": " + superType + " / " + childType);
					continue;
				}
				if (localIgnoreExtensionsMap.containsKey(superClass) && localIgnoreExtensionsMap.get(superClass) ) {
					if (superType instanceof DefinedType && childType instanceof ComplexType && ((ComplexType) childType).get("id") != null) {
						Element<?> element = ((ComplexType) childType).get("id");
						element.setProperty(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) superType).getId() + ":id"));
					}
				}
				else if (useExtensions || (localUseExtensionsMap.containsKey(superClass) && localUseExtensionsMap.get(superClass))) {
					((ModifiableType) childType).setProperty(new ValueImpl<Type>(SuperTypeProperty.getInstance(), superType));
				}
				else if (childType instanceof ModifiableComplexType) {
					List<Value<?>> values = new ArrayList<Value<?>>();
					if (childType instanceof DefinedType) {
						values.add(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) childType).getId() + ":id"));
					}
					SimpleElementImpl element = new SimpleElementImpl(elementize(superType.getName()) + "Id", idType, (ComplexType) childType, values.toArray(new Value[values.size()]));
					((ModifiableComplexType) childType).add(element);
				}
			}
			
			// load any associations (one class referencing another)
			for (org.w3c.dom.Element association : new XPath("uml:Namespace.ownedElement/uml:Association").setNamespaceContext(resolver).query(model).asElementList()) {
				String associationName = association.getAttribute("name");
				if (associationName != null && associationName.trim().isEmpty()) {
					associationName = null;
				}
				else {
					associationName += "Id";
				}
				List<org.w3c.dom.Element> ends = new XPath("uml:Association.connection/uml:AssociationEnd").setNamespaceContext(resolver).query(association).asElementList();
				if (ends.size() != 2) {
					logger.error("Can not process association with " + ends.size() + " elements, expecting 2");
					continue;
				}
				Integer fromMinOccurs = getMinOccurs(ends.get(0));
				Integer fromMaxOccurs = getMaxOccurs(ends.get(0));
				Integer toMinOccurs = getMinOccurs(ends.get(1));
				Integer toMaxOccurs = getMaxOccurs(ends.get(1));
				if (fromMaxOccurs != null && fromMaxOccurs != 1 && toMaxOccurs != null && toMaxOccurs != 1) {
					logger.error("Can not yet model many to many relations: " + fromMaxOccurs + " - " + toMaxOccurs);
					continue;
				}
				ComplexType fromParticipant = (ComplexType) getParticipant(ends.get(0));
				ComplexType toParticipant = (ComplexType) getParticipant(ends.get(1));
				if (fromParticipant == null || toParticipant == null) {
					logger.error("Could not process association because either from or to could not be found: " + fromParticipant + " / " + toParticipant);
					continue;
				}
				// we are only mapping one-one or one-many relations, this means the reference is always singular, at most "optional", never a list
				if (generateFlatDocuments) {
					List<Value<?>> values = new ArrayList<Value<?>>();
					// the "to" is a many in a one to many relationship, map it in the to
					if (toMaxOccurs != null && toMaxOccurs != 1) {
						if (fromParticipant instanceof DefinedType) {
							values.add(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) fromParticipant).getId() + ":id"));
						}
						SimpleElementImpl element = new SimpleElementImpl(associationName == null ? elementize(fromParticipant.getName()) + "Id" : associationName, idType, toParticipant, values.toArray(new Value[values.size()]));
						if (fromMinOccurs != null && fromMinOccurs != 1) {
							element.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), fromMinOccurs));
						}
						((ModifiableComplexType) toParticipant).add(element);
					}
					// in all other cases, map it in the from (this is either one to one or one to many with the many in the from)
					else {
						if (toParticipant instanceof DefinedType) {
							values.add(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) toParticipant).getId() + ":id"));
						}
						SimpleElementImpl element = new SimpleElementImpl(associationName == null ? elementize(toParticipant.getName()) + "Id" : associationName, idType, fromParticipant, values.toArray(new Value[values.size()]));
						if (toMinOccurs != null && toMinOccurs != 1) {
							element.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), toMinOccurs));
						}
						((ModifiableComplexType) fromParticipant).add(element);
					}
				}
				else {
					// the "to" is a many in a one to many relationship, map it in the to
					if (toMaxOccurs != null && toMaxOccurs != 1) {
						ComplexElementImpl element = new ComplexElementImpl(associationName == null ? elementize(fromParticipant.getName()) : associationName, fromParticipant, toParticipant);
						if (fromMinOccurs != null && fromMinOccurs != 1) {
							element.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), fromMinOccurs));
						}
						((ModifiableComplexType) toParticipant).add(element);
					}
					// in all other cases, map it in the from (this is either one to one or one to many with the many in the from)
					else {
						ComplexElementImpl element = new ComplexElementImpl(associationName == null ? elementize(toParticipant.getName()) : associationName, toParticipant, fromParticipant);
						if (toMinOccurs != null && toMinOccurs != 1) {
							element.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), toMinOccurs));
						}
						((ModifiableComplexType) fromParticipant).add(element);
					}
				}
			}
		}
	}
	
	private static String elementize(String name) {
		return name.substring(0, 1).toLowerCase() + name.substring(1);
	}

	private Integer getMinOccurs(org.w3c.dom.Element associationEnd) {
		org.w3c.dom.Element multiplicity = new XPath("uml:AssociationEnd.multiplicity/uml:Multiplicity/uml:Multiplicity.range/uml:MultiplicityRange").setNamespaceContext(resolver).query(associationEnd).asElement();
		return multiplicity == null || "-1".equals(multiplicity.getAttribute("lower")) ? null : Integer.parseInt(multiplicity.getAttribute("lower"));
	}
	private Integer getMaxOccurs(org.w3c.dom.Element associationEnd) {
		org.w3c.dom.Element multiplicity = new XPath("uml:AssociationEnd.multiplicity/uml:Multiplicity/uml:Multiplicity.range/uml:MultiplicityRange").setNamespaceContext(resolver).query(associationEnd).asElement();
		if (multiplicity == null) {
			return null;
		}
		return "-1".equals(multiplicity.getAttribute("upper")) ? 0 : Integer.parseInt(multiplicity.getAttribute("upper"));
	}
	private Type getParticipant(org.w3c.dom.Element associationEnd) {
		return dataTypes.get(new XPath("uml:AssociationEnd.participant/uml:Class/@xmi.idref").setNamespaceContext(resolver).query(associationEnd).asString());	
	}
	
	@Override
	public SimpleType<?> getSimpleType(String namespace, String name) {
		return registry.getSimpleType(namespace, name);
	}

	@Override
	public ComplexType getComplexType(String namespace, String name) {
		return registry.getComplexType(namespace, name);
	}

	@Override
	public Element<?> getElement(String namespace, String name) {
		return registry.getElement(namespace, name);
	}

	@Override
	public Set<String> getNamespaces() {
		return registry.getNamespaces();
	}

	@Override
	public List<SimpleType<?>> getSimpleTypes(String namespace) {
		return registry.getSimpleTypes(namespace);
	}

	@Override
	public List<ComplexType> getComplexTypes(String namespace) {
		return registry.getComplexTypes(namespace);
	}

	@Override
	public List<Element<?>> getElements(String namespace) {
		return registry.getElements(namespace);
	}

	public ResourceResolver getResourceResolver() {
		if (resourceResolver == null) {
			resourceResolver = new URLResourceResolver();
		}
		return resourceResolver;
	}

	public void setResourceResolver(ResourceResolver resourceResolver) {
		this.resourceResolver = resourceResolver;
	}

	@Override
	public String getId() {
		return id;
	}

	public boolean isGenerateFlatDocuments() {
		return generateFlatDocuments;
	}

	public void setGenerateFlatDocuments(boolean generateFlatDocuments) {
		this.generateFlatDocuments = generateFlatDocuments;
	}

	public boolean isAddDatabaseFields() {
		return addDatabaseFields;
	}

	public void setAddDatabaseFields(boolean addDatabaseFields) {
		this.addDatabaseFields = addDatabaseFields;
	}

	public boolean isGenerateCollectionNames() {
		return generateCollectionNames;
	}

	public void setGenerateCollectionNames(boolean generateCollectionNames) {
		this.generateCollectionNames = generateCollectionNames;
	}

	public String getCreatedField() {
		return createdField;
	}

	public void setCreatedField(String createdField) {
		this.createdField = createdField;
	}

	public String getModifiedField() {
		return modifiedField;
	}

	public void setModifiedField(String modifiedField) {
		this.modifiedField = modifiedField;
	}

	public boolean isInverseParentChildRelationship() {
		return inverseParentChildRelationship;
	}

	public void setInverseParentChildRelationship(boolean inverseParentChildRelationship) {
		this.inverseParentChildRelationship = inverseParentChildRelationship;
	}
	
}
