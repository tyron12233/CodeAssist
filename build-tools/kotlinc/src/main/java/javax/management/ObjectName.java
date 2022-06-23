package javax.management;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.io.ObjectInputStream.GetField;
import java.io.ObjectOutputStream.PutField;
import java.security.AccessController;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

public class ObjectName implements Comparable<ObjectName>, QueryExp {
    private static final int DOMAIN_PATTERN = -2147483648;
    private static final int PROPLIST_PATTERN = 1073741824;
    private static final int PROPVAL_PATTERN = 536870912;
    private static final int FLAG_MASK = -536870912;
    private static final int DOMAIN_LENGTH_MASK = 536870911;
    private static final long oldSerialVersionUID = -5467795090068647408L;
    private static final long newSerialVersionUID = 1081892073854801359L;
    private static final ObjectStreamField[] oldSerialPersistentFields;
    private static final ObjectStreamField[] newSerialPersistentFields;
    private static final long serialVersionUID;
    private static final ObjectStreamField[] serialPersistentFields;
    private static boolean compat;
    private static final ObjectName.Property[] _Empty_property_array;
    private transient String _canonicalName;
    private transient ObjectName.Property[] _kp_array;
    private transient ObjectName.Property[] _ca_array;
    private transient Map<String, String> _propertyList;
    private transient int _compressed_storage = 0;
    public static ObjectName WILDCARD = null;

    private void construct(String name) throws MalformedObjectNameException {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        } else if (name.length() == 0) {
            this._canonicalName = "*:*";
            this._kp_array = _Empty_property_array;
            this._ca_array = _Empty_property_array;
            this.setDomainLength(1);
            this._propertyList = null;
            this.setDomainPattern(true);
            this.setPropertyListPattern(true);
            this.setPropertyValuePattern(false);
        } else {
            char[] name_chars = name.toCharArray();
            int len = name_chars.length;
            char[] canonical_chars = new char[len];
            int cname_index = 0;
            int index = 0;

            label180:
            while(true) {
                int i;
                if (index < len) {
                    switch(name_chars[index]) {
                    case '\n':
                        throw new MalformedObjectNameException("Invalid character '\\n' in domain name");
                    case '*':
                    case '?':
                        this.setDomainPattern(true);
                        ++index;
                        continue;
                    case ':':
                        this.setDomainLength(index++);
                        break;
                    case '=':
                        ++index;
                        i = index;

                        do {
                            if (i >= len || name_chars[i++] == ':') {
                                continue label180;
                            }
                        } while(i != len);

                        throw new MalformedObjectNameException("Domain part must be specified");
                    default:
                        ++index;
                        continue;
                    }
                }

                if (index == len) {
                    throw new MalformedObjectNameException("Key properties cannot be empty");
                }

                i = this.getDomainLength();
                System.arraycopy(name_chars, 0, canonical_chars, 0, i);
                canonical_chars[i] = ':';
                cname_index = i + 1;
                Map<String, ObjectName.Property> keys_map = new HashMap();
                int property_index = 0;
                String[] keys = new String[10];
                this._kp_array = new ObjectName.Property[10];
                this.setPropertyListPattern(false);
                this.setPropertyValuePattern(false);

                while(true) {
                    if (index < len) {
                        char c = name_chars[index];
                        if (c != '*') {
                            int in_index = index;
                            if (name_chars[index] == '=') {
                                throw new MalformedObjectNameException("Invalid key (empty)");
                            }

                            char c1;
                            while(in_index < len && (c1 = name_chars[in_index++]) != '=') {
                                switch(c1) {
                                case '\n':
                                case '*':
                                case ',':
                                case ':':
                                case '?':
                                    String ichar = c1 == '\n' ? "\\n" : "";
                                    throw new MalformedObjectNameException("Invalid character '" + ichar + "' in key part of property");
                                }
                            }

                            if (name_chars[in_index - 1] != '=') {
                                throw new MalformedObjectNameException("Unterminated key property part");
                            }

                            int value_index = in_index;
                            int key_length = in_index - index - 1;
                            boolean value_pattern = false;
                            boolean quoted_value;
                            int value_length;
                            if (in_index < len && name_chars[in_index] == '"') {
                                quoted_value = true;

                                while(true) {
                                    ++in_index;
                                    if (in_index >= len || (c1 = name_chars[in_index]) == '"') {
                                        if (in_index == len) {
                                            throw new MalformedObjectNameException("Unterminated quoted value");
                                        }

                                        ++in_index;
                                        value_length = in_index - value_index;
                                        break;
                                    }

                                    if (c1 == '\\') {
                                        ++in_index;
                                        if (in_index == len) {
                                            throw new MalformedObjectNameException("Unterminated quoted value");
                                        }

                                        switch(c1 = name_chars[in_index]) {
                                        case '"':
                                        case '*':
                                        case '?':
                                        case '\\':
                                        case 'n':
                                            break;
                                        default:
                                            throw new MalformedObjectNameException("Invalid escape sequence '\\" + c1 + "' in quoted value");
                                        }
                                    } else {
                                        if (c1 == '\n') {
                                            throw new MalformedObjectNameException("Newline in quoted value");
                                        }

                                        switch(c1) {
                                        case '*':
                                        case '?':
                                            value_pattern = true;
                                        }
                                    }
                                }
                            } else {
                                quoted_value = false;

                                while(in_index < len && (c1 = name_chars[in_index]) != ',') {
                                    switch(c1) {
                                    case '\n':
                                    case '"':
                                    case ':':
                                    case '=':
                                        String ichar = c1 == '\n' ? "\\n" : "";
                                        throw new MalformedObjectNameException("Invalid character '" + ichar + "' in value part of property");
                                    case '*':
                                    case '?':
                                        value_pattern = true;
                                        ++in_index;
                                        break;
                                    default:
                                        ++in_index;
                                    }
                                }

                                value_length = in_index - value_index;
                            }

                            if (in_index == len - 1) {
                                if (quoted_value) {
                                    throw new MalformedObjectNameException("Invalid ending character `" + name_chars[in_index] + "'");
                                }

                                throw new MalformedObjectNameException("Invalid ending comma");
                            }

                            ++in_index;
                            Object prop;
                            if (!value_pattern) {
                                prop = new ObjectName.Property(index, key_length, value_length);
                            } else {
                                this.setPropertyValuePattern(true);
                                prop = new ObjectName.PatternProperty(index, key_length, value_length);
                            }

                            String key_name = name.substring(index, index + key_length);
                            if (property_index == keys.length) {
                                String[] tmp_string_array = new String[property_index + 10];
                                System.arraycopy(keys, 0, tmp_string_array, 0, property_index);
                                keys = tmp_string_array;
                            }

                            keys[property_index] = key_name;
                            this.addProperty((ObjectName.Property)prop, property_index, keys_map, key_name);
                            ++property_index;
                            index = in_index;
                            continue;
                        }

                        if (this.isPropertyListPattern()) {
                            throw new MalformedObjectNameException("Cannot have several '*' characters in pattern property list");
                        }

                        this.setPropertyListPattern(true);
                        ++index;
                        if (index < len && name_chars[index] != ',') {
                            throw new MalformedObjectNameException("Invalid character found after '*': end of name or ',' expected");
                        }

                        if (index != len) {
                            ++index;
                            continue;
                        }

                        if (property_index == 0) {
                            this._kp_array = _Empty_property_array;
                            this._ca_array = _Empty_property_array;
                            this._propertyList = Collections.emptyMap();
                        }
                    }

                    this.setCanonicalName(name_chars, canonical_chars, keys, keys_map, cname_index, property_index);
                    return;
                }
            }
        }
    }

    private void construct(String domain, Map<String, String> props) throws MalformedObjectNameException {
        if (domain == null) {
            throw new NullPointerException("domain cannot be null");
        } else if (props == null) {
            throw new NullPointerException("key property list cannot be null");
        } else if (props.isEmpty()) {
            throw new MalformedObjectNameException("key property list cannot be empty");
        } else if (!this.isDomain(domain)) {
            throw new MalformedObjectNameException("Invalid domain: " + domain);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(domain).append(':');
            this.setDomainLength(domain.length());
            int nb_props = props.size();
            this._kp_array = new ObjectName.Property[nb_props];
            String[] keys = new String[nb_props];
            Map<String, ObjectName.Property> keys_map = new HashMap();
            int i = 0;

            for(Iterator var10 = props.entrySet().iterator(); var10.hasNext(); ++i) {
                Entry<String, String> entry = (Entry)var10.next();
                if (sb.length() > 0) {
                    sb.append(",");
                }

                String key = (String)entry.getKey();

                String value;
                try {
                    value = (String)entry.getValue();
                } catch (ClassCastException var15) {
                    throw new MalformedObjectNameException(var15.getMessage());
                }

                int key_index = sb.length();
                checkKey(key);
                sb.append(key);
                keys[i] = key;
                sb.append("=");
                boolean value_pattern = checkValue(value);
                sb.append(value);
                Object prop;
                if (!value_pattern) {
                    prop = new ObjectName.Property(key_index, key.length(), value.length());
                } else {
                    this.setPropertyValuePattern(true);
                    prop = new ObjectName.PatternProperty(key_index, key.length(), value.length());
                }

                this.addProperty((ObjectName.Property)prop, i, keys_map, key);
            }

            int len = sb.length();
            char[] initial_chars = new char[len];
            sb.getChars(0, len, initial_chars, 0);
            char[] canonical_chars = new char[len];
            int copyLen = this.getDomainLength() + 1;
            System.arraycopy(initial_chars, 0, canonical_chars, 0, copyLen);
            this.setCanonicalName(initial_chars, canonical_chars, keys, keys_map, copyLen, this._kp_array.length);
        }
    }

    private void addProperty(ObjectName.Property prop, int index, Map<String, ObjectName.Property> keys_map, String key_name) throws MalformedObjectNameException {
        if (keys_map.containsKey(key_name)) {
            throw new MalformedObjectNameException("key `" + key_name + "' already defined");
        } else {
            if (index == this._kp_array.length) {
                ObjectName.Property[] tmp_prop_array = new ObjectName.Property[index + 10];
                System.arraycopy(this._kp_array, 0, tmp_prop_array, 0, index);
                this._kp_array = tmp_prop_array;
            }

            this._kp_array[index] = prop;
            keys_map.put(key_name, prop);
        }
    }

    private void setCanonicalName(char[] specified_chars, char[] canonical_chars, String[] keys, Map<String, ObjectName.Property> keys_map, int prop_index, int nb_props) {
        if (this._kp_array != _Empty_property_array) {
            String[] tmp_keys = new String[nb_props];
            ObjectName.Property[] tmp_props = new ObjectName.Property[nb_props];
            System.arraycopy(keys, 0, tmp_keys, 0, nb_props);
            Arrays.sort(tmp_keys);
            keys = tmp_keys;
            System.arraycopy(this._kp_array, 0, tmp_props, 0, nb_props);
            this._kp_array = tmp_props;
            this._ca_array = new ObjectName.Property[nb_props];

            int last_index;
            for(last_index = 0; last_index < nb_props; ++last_index) {
                this._ca_array[last_index] = (ObjectName.Property)keys_map.get(keys[last_index]);
            }

            last_index = nb_props - 1;

            for(int i = 0; i <= last_index; ++i) {
                ObjectName.Property prop = this._ca_array[i];
                int prop_len = prop._key_length + prop._value_length + 1;
                System.arraycopy(specified_chars, prop._key_index, canonical_chars, prop_index, prop_len);
                prop.setKeyIndex(prop_index);
                prop_index += prop_len;
                if (i != last_index) {
                    canonical_chars[prop_index] = ',';
                    ++prop_index;
                }
            }
        }

        if (this.isPropertyListPattern()) {
            if (this._kp_array != _Empty_property_array) {
                canonical_chars[prop_index++] = ',';
            }

            canonical_chars[prop_index++] = '*';
        }

        this._canonicalName = (new String(canonical_chars, 0, prop_index)).intern();
    }

    private static int parseKey(char[] s, int startKey) throws MalformedObjectNameException {
        int next = startKey;
        int endKey = startKey;
        int len = s.length;

        while(true) {
            if (next < len) {
                char k = s[next++];
                switch(k) {
                case '\n':
                case '*':
                case ',':
                case ':':
                case '?':
                    String ichar = k == '\n' ? "\\n" : "";
                    throw new MalformedObjectNameException("Invalid character in key: `" + ichar + "'");
                case '=':
                    endKey = next - 1;
                    break;
                default:
                    if (next < len) {
                        continue;
                    }

                    endKey = next;
                }
            }

            return endKey;
        }
    }

    private static int[] parseValue(char[] s, int startValue) throws MalformedObjectNameException {
        boolean value_pattern = false;
        int next = startValue;
        int endValue = startValue;
        int len = s.length;
        char q = s[startValue];
        char last;
        if (q == '"') {
            next = startValue + 1;
            if (next == len) {
                throw new MalformedObjectNameException("Invalid quote");
            }

            while(next < len) {
                last = s[next];
                if (last == '\\') {
                    ++next;
                    if (next == len) {
                        throw new MalformedObjectNameException("Invalid unterminated quoted character sequence");
                    }

                    last = s[next];
                    switch(last) {
                    case '"':
                        if (next + 1 == len) {
                            throw new MalformedObjectNameException("Missing termination quote");
                        }
                    case '*':
                    case '?':
                    case '\\':
                    case 'n':
                        break;
                    default:
                        throw new MalformedObjectNameException("Invalid quoted character sequence '\\" + last + "'");
                    }
                } else {
                    if (last == '\n') {
                        throw new MalformedObjectNameException("Newline in quoted value");
                    }

                    if (last == '"') {
                        ++next;
                        break;
                    }

                    switch(last) {
                    case '*':
                    case '?':
                        value_pattern = true;
                    }
                }

                ++next;
                if (next >= len && last != '"') {
                    throw new MalformedObjectNameException("Missing termination quote");
                }
            }

            endValue = next;
            if (next < len && s[next++] != ',') {
                throw new MalformedObjectNameException("Invalid quote");
            }
        } else {
            while(next < len) {
                last = s[next++];
                switch(last) {
                case '\n':
                case ':':
                case '=':
                    String ichar = last == '\n' ? "\\n" : "";
                    throw new MalformedObjectNameException("Invalid character `" + ichar + "' in value");
                case '*':
                case '?':
                    value_pattern = true;
                    if (next >= len) {
                        endValue = next;
                        return new int[]{endValue, value_pattern ? 1 : 0};
                    }
                    break;
                case ',':
                    endValue = next - 1;
                    return new int[]{endValue, value_pattern ? 1 : 0};
                default:
                    if (next >= len) {
                        endValue = next;
                        return new int[]{endValue, value_pattern ? 1 : 0};
                    }
                }
            }
        }

        return new int[]{endValue, value_pattern ? 1 : 0};
    }

    private static boolean checkValue(String val) throws MalformedObjectNameException {
        if (val == null) {
            throw new NullPointerException("Invalid value (null)");
        } else {
            int len = val.length();
            if (len == 0) {
                return false;
            } else {
                char[] s = val.toCharArray();
                int[] result = parseValue(s, 0);
                int endValue = result[0];
                boolean value_pattern = result[1] == 1;
                if (endValue < len) {
                    throw new MalformedObjectNameException("Invalid character in value: `" + s[endValue] + "'");
                } else {
                    return value_pattern;
                }
            }
        }
    }

    private static void checkKey(String key) throws MalformedObjectNameException {
        if (key == null) {
            throw new NullPointerException("Invalid key (null)");
        } else {
            int len = key.length();
            if (len == 0) {
                throw new MalformedObjectNameException("Invalid key (empty)");
            } else {
                char[] k = key.toCharArray();
                int endKey = parseKey(k, 0);
                if (endKey < len) {
                    throw new MalformedObjectNameException("Invalid character in value: `" + k[endKey] + "'");
                }
            }
        }
    }

    private boolean isDomain(String domain) {
        if (domain == null) {
            return true;
        } else {
            int len = domain.length();
            int next = 0;

            while(next < len) {
                char c = domain.charAt(next++);
                switch(c) {
                case '\n':
                case ':':
                    return false;
                case '*':
                case '?':
                    this.setDomainPattern(true);
                }
            }

            return true;
        }
    }

    private int getDomainLength() {
        return this._compressed_storage & 536870911;
    }

    private void setDomainLength(int length) throws MalformedObjectNameException {
        if ((length & -536870912) != 0) {
            throw new MalformedObjectNameException("Domain name too long. Maximum allowed domain name length is:536870911");
        } else {
            this._compressed_storage = this._compressed_storage & -536870912 | length;
        }
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        String cn;
        if (compat) {
            GetField fields = in.readFields();
            String propListString = (String)fields.get("propertyListString", "");
            boolean propPattern = fields.get("propertyPattern", false);
            if (propPattern) {
                propListString = propListString.length() == 0 ? "*" : propListString + ",*";
            }

            String var10000 = (String)fields.get("domain", "default");
            cn = var10000 + ":" + propListString;
        } else {
            in.defaultReadObject();
            cn = (String)in.readObject();
        }

        try {
            this.construct(cn);
        } catch (NullPointerException var6) {
            throw new InvalidObjectException(var6.toString());
        } catch (MalformedObjectNameException var7) {
            throw new InvalidObjectException(var7.toString());
        }
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        if (compat) {
            PutField fields = out.putFields();
            fields.put("domain", this._canonicalName.substring(0, this.getDomainLength()));
            fields.put("propertyList", this.getKeyPropertyList());
            fields.put("propertyListString", this.getKeyPropertyListString());
            fields.put("canonicalName", this._canonicalName);
            fields.put("pattern", (this._compressed_storage & -1073741824) != 0);
            fields.put("propertyPattern", this.isPropertyListPattern());
            out.writeFields();
        } else {
            out.defaultWriteObject();
            out.writeObject(this.getSerializedNameString());
        }

    }

    public static ObjectName getInstance(String name) throws MalformedObjectNameException, NullPointerException {
        return new ObjectName(name);
    }

    public static ObjectName getInstance(String domain, String key, String value) throws MalformedObjectNameException {
        return new ObjectName(domain, key, value);
    }

    public static ObjectName getInstance(String domain, Hashtable<String, String> table) throws MalformedObjectNameException {
        return new ObjectName(domain, table);
    }

    public static ObjectName getInstance(ObjectName name) {
        return name.getClass().equals(ObjectName.class) ? name : name;
    }

    public ObjectName(String name) throws MalformedObjectNameException {
        this.construct(name);
    }

    public ObjectName(String domain, String key, String value) throws MalformedObjectNameException {
        Map<String, String> table = Collections.singletonMap(key, value);
        this.construct(domain, table);
    }

    public ObjectName(String domain, Hashtable<String, String> table) throws MalformedObjectNameException {
        this.construct(domain, table);
    }

    public boolean isPattern() {
        return (this._compressed_storage & -536870912) != 0;
    }

    public boolean isDomainPattern() {
        return (this._compressed_storage & -2147483648) != 0;
    }

    private void setDomainPattern(boolean value) {
        if (value) {
            this._compressed_storage |= -2147483648;
        } else {
            this._compressed_storage &= 2147483647;
        }

    }

    public boolean isPropertyPattern() {
        return (this._compressed_storage & 1610612736) != 0;
    }

    public boolean isPropertyListPattern() {
        return (this._compressed_storage & 1073741824) != 0;
    }

    private void setPropertyListPattern(boolean value) {
        if (value) {
            this._compressed_storage |= 1073741824;
        } else {
            this._compressed_storage &= -1073741825;
        }

    }

    public boolean isPropertyValuePattern() {
        return (this._compressed_storage & 536870912) != 0;
    }

    private void setPropertyValuePattern(boolean value) {
        if (value) {
            this._compressed_storage |= 536870912;
        } else {
            this._compressed_storage &= -536870913;
        }

    }

    public boolean isPropertyValuePattern(String property) {
        if (property == null) {
            throw new NullPointerException("key property can't be null");
        } else {
            for(int i = 0; i < this._ca_array.length; ++i) {
                ObjectName.Property prop = this._ca_array[i];
                String key = prop.getKeyString(this._canonicalName);
                if (key.equals(property)) {
                    return prop instanceof ObjectName.PatternProperty;
                }
            }

            throw new IllegalArgumentException("key property not found");
        }
    }

    public String getCanonicalName() {
        return this._canonicalName;
    }

    public String getDomain() {
        return this._canonicalName.substring(0, this.getDomainLength());
    }

    public String getKeyProperty(String property) {
        return (String)this._getKeyPropertyList().get(property);
    }

    private Map<String, String> _getKeyPropertyList() {
        synchronized(this) {
            if (this._propertyList == null) {
                this._propertyList = new HashMap();
                int len = this._ca_array.length;

                for(int i = len - 1; i >= 0; --i) {
                    ObjectName.Property prop = this._ca_array[i];
                    this._propertyList.put(prop.getKeyString(this._canonicalName), prop.getValueString(this._canonicalName));
                }
            }
        }

        return this._propertyList;
    }

    public Hashtable<String, String> getKeyPropertyList() {
        return new Hashtable(this._getKeyPropertyList());
    }

    public String getKeyPropertyListString() {
        if (this._kp_array.length == 0) {
            return "";
        } else {
            int total_size = this._canonicalName.length() - this.getDomainLength() - 1 - (this.isPropertyListPattern() ? 2 : 0);
            char[] dest_chars = new char[total_size];
            char[] value = this._canonicalName.toCharArray();
            this.writeKeyPropertyListString(value, dest_chars, 0);
            return new String(dest_chars);
        }
    }

    private String getSerializedNameString() {
        int total_size = this._canonicalName.length();
        char[] dest_chars = new char[total_size];
        char[] value = this._canonicalName.toCharArray();
        int offset = this.getDomainLength() + 1;
        System.arraycopy(value, 0, dest_chars, 0, offset);
        int end = this.writeKeyPropertyListString(value, dest_chars, offset);
        if (this.isPropertyListPattern()) {
            if (end == offset) {
                dest_chars[end] = '*';
            } else {
                dest_chars[end] = ',';
                dest_chars[end + 1] = '*';
            }
        }

        return new String(dest_chars);
    }

    private int writeKeyPropertyListString(char[] canonicalChars, char[] data, int offset) {
        if (this._kp_array.length == 0) {
            return offset;
        } else {
            char[] dest_chars = data;
            char[] value = canonicalChars;
            int index = offset;
            int len = this._kp_array.length;
            int last = len - 1;

            for(int i = 0; i < len; ++i) {
                ObjectName.Property prop = this._kp_array[i];
                int prop_len = prop._key_length + prop._value_length + 1;
                System.arraycopy(value, prop._key_index, dest_chars, index, prop_len);
                index += prop_len;
                if (i < last) {
                    dest_chars[index++] = ',';
                }
            }

            return index;
        }
    }

    public String getCanonicalKeyPropertyListString() {
        if (this._ca_array.length == 0) {
            return "";
        } else {
            int len = this._canonicalName.length();
            if (this.isPropertyListPattern()) {
                len -= 2;
            }

            return this._canonicalName.substring(this.getDomainLength() + 1, len);
        }
    }

    public String toString() {
        return this.getSerializedNameString();
    }

    public boolean equals(Object object) {
        if (this == object) {
            return true;
        } else if (!(object instanceof ObjectName)) {
            return false;
        } else {
            ObjectName on = (ObjectName)object;
            String on_string = on._canonicalName;
            return this._canonicalName == on_string;
        }
    }

    public int hashCode() {
        return this._canonicalName.hashCode();
    }

    public static String quote(String s) {
        StringBuilder buf = new StringBuilder("\"");
        int len = s.length();

        for(int i = 0; i < len; ++i) {
            char c = s.charAt(i);
            switch(c) {
            case '\n':
                c = 'n';
                buf.append('\\');
                break;
            case '"':
            case '*':
            case '?':
            case '\\':
                buf.append('\\');
            }

            buf.append(c);
        }

        buf.append('"');
        return buf.toString();
    }

    public static String unquote(String q) {
        StringBuilder buf = new StringBuilder();
        int len = q.length();
        if (len >= 2 && q.charAt(0) == '"' && q.charAt(len - 1) == '"') {
            for(int i = 1; i < len - 1; ++i) {
                char c = q.charAt(i);
                if (c == '\\') {
                    if (i == len - 2) {
                        throw new IllegalArgumentException("Trailing backslash");
                    }

                    ++i;
                    c = q.charAt(i);
                    switch(c) {
                    case '"':
                    case '*':
                    case '?':
                    case '\\':
                        break;
                    case 'n':
                        c = '\n';
                        break;
                    default:
                        throw new IllegalArgumentException("Bad character '" + c + "' after backslash");
                    }
                } else {
                    switch(c) {
                    case '\n':
                    case '"':
                    case '*':
                    case '?':
                        throw new IllegalArgumentException("Invalid unescaped character '" + c + "' in the string to unquote");
                    }
                }

                buf.append(c);
            }

            return buf.toString();
        } else {
            throw new IllegalArgumentException("Argument not quoted");
        }
    }

    public boolean apply(ObjectName name) {
        if (name == null) {
            throw new NullPointerException();
        } else if (name.isPattern()) {
            return false;
        } else if (!this.isPattern()) {
            return this._canonicalName.equals(name._canonicalName);
        } else {
            return this.matchDomains(name) && this.matchKeys(name);
        }
    }

    private final boolean matchDomains(ObjectName name) {
//        return this.isDomainPattern() ? Util.wildmatch(name.getDomain(), this.getDomain()) : this.getDomain().equals(name.getDomain());
        return false;
    }

    private final boolean matchKeys(ObjectName name) {
        if (this.isPropertyValuePattern() && !this.isPropertyListPattern() && name._ca_array.length != this._ca_array.length) {
            return false;
        } else if (!this.isPropertyPattern()) {
            String p1 = name.getCanonicalKeyPropertyListString();
            String p2 = this.getCanonicalKeyPropertyListString();
            return p1.equals(p2);
        } else {
            Map<String, String> nameProps = name._getKeyPropertyList();
            ObjectName.Property[] props = this._ca_array;
            String cn = this._canonicalName;

            for(int i = props.length - 1; i >= 0; --i) {
                ObjectName.Property p = props[i];
                String k = p.getKeyString(cn);
                String v = (String)nameProps.get(k);
                if (v == null) {
                    return false;
                }

                if (this.isPropertyValuePattern() && p instanceof ObjectName.PatternProperty) {

                } else if (!v.equals(p.getValueString(cn))) {
                    return false;
                }
            }

            return true;
        }
    }

    public void setMBeanServer(MBeanServer mbs) {
    }

    public int compareTo(ObjectName name) {
        if (name == this) {
            return 0;
        } else {
            int domainValue = this.getDomain().compareTo(name.getDomain());
            if (domainValue != 0) {
                return domainValue;
            } else {
                String thisTypeKey = this.getKeyProperty("type");
                String anotherTypeKey = name.getKeyProperty("type");
                if (thisTypeKey == null) {
                    thisTypeKey = "";
                }

                if (anotherTypeKey == null) {
                    anotherTypeKey = "";
                }

                int typeKeyValue = thisTypeKey.compareTo(anotherTypeKey);
                return typeKeyValue != 0 ? typeKeyValue : this.getCanonicalName().compareTo(name.getCanonicalName());
            }
        }
    }

    static {
        oldSerialPersistentFields = new ObjectStreamField[]{new ObjectStreamField("domain", String.class), new ObjectStreamField("propertyList", Hashtable.class), new ObjectStreamField("propertyListString", String.class), new ObjectStreamField("canonicalName", String.class), new ObjectStreamField("pattern", Boolean.TYPE), new ObjectStreamField("propertyPattern", Boolean.TYPE)};
        newSerialPersistentFields = new ObjectStreamField[0];
        compat = false;

//        try {
//            GetPropertyAction act = new GetPropertyAction("jmx.serial.form");
//            String form = (String)AccessController.doPrivileged(act);
//            compat = form != null && form.equals("1.0");
//        } catch (Exception var2) {
//        }

        if (compat) {
            serialPersistentFields = oldSerialPersistentFields;
            serialVersionUID = -5467795090068647408L;
        } else {
            serialPersistentFields = newSerialPersistentFields;
            serialVersionUID = 1081892073854801359L;
        }

        _Empty_property_array = new ObjectName.Property[0];
        try {
            WILDCARD = new ObjectName("");
        } catch (MalformedObjectNameException e) {
            e.printStackTrace();
        }
    }

    private static class PatternProperty extends ObjectName.Property {
        PatternProperty(int key_index, int key_length, int value_length) {
            super(key_index, key_length, value_length);
        }
    }

    private static class Property {
        int _key_index;
        int _key_length;
        int _value_length;

        Property(int key_index, int key_length, int value_length) {
            this._key_index = key_index;
            this._key_length = key_length;
            this._value_length = value_length;
        }

        void setKeyIndex(int key_index) {
            this._key_index = key_index;
        }

        String getKeyString(String name) {
            return name.substring(this._key_index, this._key_index + this._key_length);
        }

        String getValueString(String name) {
            int in_begin = this._key_index + this._key_length + 1;
            int out_end = in_begin + this._value_length;
            return name.substring(in_begin, out_end);
        }
    }
}