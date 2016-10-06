# UML Registry

The UML file(s) can be loaded into a registry. You need to use some "standards" to make it work in the current implementation.

## Data Types

The "attributes" use "data types". These types are defined elsewhere (in argouml for example on the site itself).
This means that in the default scenario in argoUML you get something like this:

```xml
<UML:DataType href = 'http://argouml.org/profiles/uml14/default-uml14.xmi#-84-17--56-5-43645a83:11466542d86:-8000:000000000000087E'/>
```

There are a couple of problems with this:

- you can't "simply" resolve a HTTP url anywhere. There might be firewalls, proxies, authentication,... in the way
- the default data types they offer are very limited

To make it as easy as possible, the current implementation uses the default data types available in XML Schema.
This means the types should have names that match XML Schema types like "string", "boolean", "int", "integer",...

To take the example of argoUML you could create your own data types with the correct xsd names in your own model (they are only matched on name). It is however easier to have them predefined, as such a "baseTypes.xmi" is provided by this project which already defines all the supported types. In argoUML right click on "Profile Configuration > Manage Profiles". In the second tab "Profiles", press "Load profile from file..." and load the "baseTypes.xmi" that is included in this project. 

Next you still need to enable it for the uml you are working on so select it in the left column and move it to the right. At this point you will be able to see the predefined types in the "type" dropdown for attributes.

## Tags

Tags also have an equivalent XML Schema counterpart, for example in XML Schema you can put all sorts of attributes on a string: pattern, maxLength, minLength,...

The logic for tags is the same as for data types: they are mapped by name to their XML Schema counterpart, as such you can create your own tags in your own model. Alternatively the default tags are also embedded in the `baseTypes.xmi` mentioned above.

Note that in XML Schema not all attributes are applicable to all types but such restrictions can not be put on the provided tags (as far as I know), so it is possible to select a tag that is not applicable for a type. This does not cause any exceptions, it is simply ignored. 

**Important**: minOccurs and maxOccurs are **not** exposed as separate attributes as they already exist in the form of "Multiplicity" which is standard in UML. Anything else is selectable from the tags once you have baseTypes loaded. 
