/*
 * This software is public 
 */
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class for parsing and formatting roman numbers. See {@link LargeNumeralMode} and {@link NumeralSymbolMode} for additional
 * details. Instances of this class are immutable.
 * 
 * @author treblada
 */
public class RomanNumeral extends Number {

	/** Default logger for this class */
	private static final Logger log = Logger.getLogger(RomanNumeral.class.getName());

	/**
	 * The processing type for large number notation. This class supports following types for large number notation:
	 * <ol>
	 * <li>{@link #SIMPLE}: Only basic numerals are allowed, namely <code>I</code>, <code>V</code>, <code>X</code>, <code>L</code>, <code>C</code>, <code>D</code>, <code>M</code></li>
	 * <li>{@link #APOSTROPHUS}: Large numbers may be encoded as combinations of <code>C</code>, the apostrophus <code>|</code>
	 * and the reversed C encoded either as <code>Ͻ</code> or as closing parenthesis <code>)</code>. Basisically these characters
	 * stand for <code>C|Ͻ</code>=1,000, <code>CC|ϽϽ</code>=10,000 and so on. The half values are noted without leading
	 * <code>C</code>: <code>|Ͻ</code>=500, <code>|ϽϽ</code>=5,000 and so on.</li>
	 * <li>{@link #CIFRAO}: The character <code>$</code> is used as separator for thousand values, e.g. <code>I$</code>=1,000,
	 * <code>V$L</code>=1,050 and <code>X$$</code>=10,000,000.</li>
	 * </ol>
	 * 
	 * @author treblada
	 */
	public static enum LargeNumeralMode {
		/**
		 * Only simple symbols are allowed (simply spoken: only letters), other characters will be treated as invalid input
		 */
		SIMPLE("%1$s"),
		/**
		 * Large numbers will be encoded (and parsed) using an roman apostrophus.
		 */
		APOSTROPHUS("(C+|[Ͻ\\)]+)*%1$s"),
		/**
		 * Large number will be encoded (and parsed) using a cifrão / calderón which is simmilar to the Dollar sign
		 */
		CIFRAO("((%1$s)\\$+)*%1$s"), ;

		/** Regular expression for number validation */
		private String regEx;

		private LargeNumeralMode(String regEx) {
			this.regEx = regEx;
		}

		/**
		 * Returns a regular expression patter for validation of numbers
		 * 
		 * @param numberMode mode for number processing - will be included in the pattern
		 * @return a patter for validation of roman number expressions
		 */
		public Pattern getPattern(NumeralSymbolMode numberMode) {
			return Pattern.compile(String.format(regEx, numberMode.getPattern().pattern()), numberMode.getPattern().flags());
		}
	}

	/**
	 * Processing mode for number symbols.
	 * 
	 * @author treblada
	 */
	public static enum NumeralSymbolMode {
		/**
		 * Accept only primitive input. Symbols must be strictly sorted (MDCLXVI), no subtraction rule applies.
		 */
		PRIMITIVE("M*D*C*L*X*V*I*"),
		/**
		 * Accept input with strictly checked subtraction rule. Symbols must be strictly sorted (MDCLXVI). Onyl the symbols M/C/X
		 * may be preceded by C,D/X,L/V,I which then will be subtracted. This is the most common form.
		 */
		STRICT("([CD]?M+)?D*([XL]?C+)?L*([VI]?X+)?V*I*"),
		/**
		 * Relaxed form of the subtraction rule. Any symbol preceeded by a symbol of lower value will be diminished by it.
		 */
		RELAXED("[MDCLXVI]*"), ;

		private final Pattern pattern;

		private NumeralSymbolMode(String regEx) {
			this.pattern = Pattern.compile(regEx, Pattern.CASE_INSENSITIVE);
		}

		/**
		 * Returns a validation pattern for number symbols
		 */
		public Pattern getPattern() {
			return pattern;
		}
	}

	/** Apostrophus group parsing phases */
	private static enum Phase {
		/** Phase for parsing closing characters */
		CLOSING,
		/** Phase for parsing opening characters */
		OPENING
	}

	/**
	 * An iterator wrapper which keeps count if its {@link #next()} calls
	 */
	private static final class CountingIterator<T> implements Iterator<T> {

		/** Counter for {@link #next()} calls */
		private int calls = 0;
		/** Wrapped iterator */
		private final Iterator<T> iter;

		/**
		 * Constructor for counting iterator
		 * 
		 * @param iter the iterator to wrap
		 * @throws NullPointerException if the iterator argument is <code>null</code>.
		 */
		private CountingIterator(Iterator<T> iter) {
			if (iter == null) {
				throw new NullPointerException("Iterator must not be NULL");
			}
			this.iter = iter;
		}

		public boolean hasNext() {
			return iter.hasNext();
		}

		public T next() {
			++calls;
			return iter.next();
		}

		public void remove() {
			iter.remove();
		}

		/**
		 * Returns the number of calls to the {@link #next()} method.
		 * 
		 * @return number of calls to the {@link #next()} method.
		 */
		public int getNextCalls() {
			return calls;
		}
	}

	/**
	 * Abstraction of supported roman numbers with values
	 * 
	 * @author treblada
	 */
	private static enum RomanChar {
		/** Roman thousand */
		M(1000, 'M'),
		/** Roman half thousand */
		D(500, 'D'),
		/** Roman hundred */
		C(100, 'C'),
		/** Roman half hundred */
		L(50, 'L'),
		/** Roman ten */
		X(10, 'X'),
		/** Roman half ten */
		V(5, 'V'),
		/** Roman one */
		I(1, 'I'),
		/** Cifrão oder Calderón (thousand multiplicator) */
		CIF(-1, '$'),
		/** Roman apostrophus - special marking for (half) thousand symbols */
		APO(-2, '|'),
		/** Place holder for mirrored "C" used along with apostrophus and {@link #C} */
		_C(-3, 'Ͻ', ')'),

		;

		/** Internal mapping of characters to enumerations */
		private final static Map<Character, RomanChar> byChar = new HashMap<Character, RomanChar>();
		/** Internal mapping of number values to roman characters - only for positive values */
		private final static TreeMap<Integer, RomanChar> byValue = new TreeMap<Integer, RomanChar>();

		// init static maps
		static {
			for (RomanChar c : values()) {
				byChar.put(c.getChar(), c);
				for (char o : c.other) {
					byChar.put(o, c);
				}
				if (c.getVal() >= 0) {
					byValue.put(c.getVal(), c);
				}
			}
		}

		/** Internal representation of the roman character */
		private final char c;
		/** Internal representation of the characters value - negative for symbols */
		private final int v;
		/** Other possible characters for this symbol */
		private final char[] other;

		/**
		 * A new roman enum value with a character and value
		 * 
		 * @param v it's value - negative for symbolic values
		 * @param c symbol for roman number - the leading symbol
		 * @param other other possible representation of this symbol
		 */
		private RomanChar(int v, char c, char... other) {
			this.v = v;
			this.c = c;
			this.other = other;
		}

		/**
		 * Returns the roman character for this symbol
		 */
		public char getChar() {
			return c;
		}

		/**
		 * Returns the numeric value for the roman symbol
		 */
		public int getVal() {
			return v;
		}

		/**
		 * Returns <code>true</code> if this symbol does not stand for a cardinal number but is a modificator for other values.
		 */
		public boolean isSymbolic() {
			return v < 0;
		}

		/**
		 * Returns a symbol for a given character
		 * 
		 * @param c the roman character to decode
		 * @return the symbol for the character
		 * @throws IllegalArgumentException if the character has no roman symbol
		 */
		public static RomanChar byChar(char c) {
			RomanChar rc = byChar.get(c);
			if (rc == null) {
				throw new IllegalArgumentException("Not a roman number character: '" + c + "'");
			}
			return rc;
		}

		/**
		 * Returns the best match for a value
		 * 
		 * @param val a number value to match
		 * @return roman symbol with a value equal or smaller than <code>val</code>.
		 */
		public static RomanChar byNextValue(int val) {
			if (val < 1) {
				throw new IllegalArgumentException("Values must be strictly positive");
			}
			Entry<Integer, RomanChar> e = byValue.floorEntry(val);
			if (e == null) {
				throw new NullPointerException("There is no valid entry for " + val);
			} else {
				return e.getValue();
			}
		}

		@Override
		public String toString() {
			return Character.toString(getChar());
		}
	}

	private static final long serialVersionUID = 1067211974469560316L;

	/** Value of this number */
	private final long value;

	/**
	 * Create a new roman number with a particular value
	 * 
	 * @param value the value for the number
	 */
	public RomanNumeral(long value) {
		super();
		if (value < 0) {
			throw new IllegalArgumentException("Invalid value for a roman number: " + value);
		}
		this.value = value;
	}

	@Override
	public int intValue() {
		return (int) value;
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public float floatValue() {
		return (float) value;
	}

	@Override
	public double doubleValue() {
		return (double) value;
	}

	/**
	 * Returns the representation of this number. The formatting uses {@link LargeNumeralMode#SIMPLE} and
	 * {@link NumeralSymbolMode#STRICT}
	 */
	@Override
	public String toString() {
		return toString(NumeralSymbolMode.STRICT);
	}

	/**
	 * Returns the representation of this number.
	 * 
	 * @param mode mode for number formatting.
	 * @return representation of the string
	 */
	public String toString(NumeralSymbolMode mode) {
		return toString(LargeNumeralMode.SIMPLE, mode);
	}

	/**
	 * Returns the representation of this number.
	 * 
	 * @param largeMode mode for representation of large numbers
	 * @param numberMode mode for representation of simple symbols
	 * @return
	 */
	public String toString(LargeNumeralMode largeMode, NumeralSymbolMode numberMode) {
		return toString(value, largeMode, numberMode);
	}

	/**
	 * Produces a roman representation of a number. This calls {@link #toString(NumeralSymbolMode)} with
	 * {@link NumeralSymbolMode#STRICT}
	 * 
	 * @param val value which should be converted to roman numbers
	 * @return roman number string
	 */
	public static String toString(long val) {
		return toString(val, NumeralSymbolMode.STRICT);
	}

	/**
	 * Produces a roman representation of a number. This calls {@link #toString(LargeNumeralMode, NumeralSymbolMode)} with
	 * {@link LargeNumeralMode#SIMPLE}
	 * 
	 * @param val value which should be converted to roman numbers
	 * @param mode mode for representation of digit symbols
	 * @return roman number string
	 */
	public static String toString(long val, NumeralSymbolMode mode) {
		return toString(val, LargeNumeralMode.SIMPLE, mode);
	}

	/**
	 * Returns a string reprentation (roman number) of a number value
	 * 
	 * @param val value to show as roman number
	 * @param largeMode mode for representation of large number
	 * @param numberMode mode for representation of simple symbols
	 * @return string with characters representing a roman number
	 * @throws IllegalArgumentException if value is negative or larger than {@value Integer#MAX_VALUE}
	 */
	public static String toString(long val, LargeNumeralMode largeMode, NumeralSymbolMode numberMode) {
		if (val < 0) {
			throw new IllegalArgumentException("Number must be non-negative: " + val);
		} else if (val > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Value is too large (>" + Integer.MAX_VALUE);
		}
		switch (largeMode) {
			case APOSTROPHUS:
				return toStringWithApostrophus(val, numberMode);
			case CIFRAO:
				return toStringWithCifrao(val, numberMode);
			case SIMPLE:
				return toStringSimple(val, numberMode);
			default:
				throw new IllegalArgumentException("Unknown large number mode " + largeMode);
		}
	}

	/**
	 * Compresses multiple characters in the output string: <code>IIII</code> becomes <code>IV</code>, <code>VIIII</code> becomes
	 * <code>IX</code> and so on.
	 * 
	 * @param s string to parse
	 * @return roman number symbols
	 */
	private static String compress(String s) {
		s = s.replace("DCCCC", "CM");
		s = s.replace("CCCC", "CD");
		s = s.replace("LXXXX", "XC");
		s = s.replace("XXXX", "XL");
		s = s.replace("VIIII", "IX");
		s = s.replace("IIII", "IV");
		return s;
	}

	/**
	 * Returns the value as roman number in very simple matter - no encoding of large numbers will be performed
	 * 
	 * @param val value to display
	 * @return roman representation of the number
	 * @throws IllegalArgumentException if the value is negative
	 */
	private static String toStringSimple(long val, NumeralSymbolMode numberMode) {
		StringBuilder b = new StringBuilder();
		while (val > 0) {
			RomanChar c = RomanChar.byNextValue((int) val);
			b.append(c);
			val -= c.getVal();
			if (val < 0) {
				throw new RuntimeException("Error in calculations: remainder=" + val);
			}
		}
		return numberMode != NumeralSymbolMode.PRIMITIVE ? compress(b.toString()) : b.toString();
	}

	/**
	 * Returns the value as roman number where values above 5000 will be encoded using apostrophus notation.
	 * 
	 * @param val value to display
	 * @return roman representation of the number
	 * @throws IllegalArgumentException if the value is negative
	 */
	private static String toStringWithApostrophus(long val, NumeralSymbolMode mode) {
		if (val < 0) {
			throw new IllegalArgumentException("Number must be non-negative: " + val);
		} else if (val < 5000) {
			return toStringSimple(val, mode);
		} else {
			StringBuilder b = new StringBuilder();
			long r = val / 1000;
			for (int arcs = 1; r > 0; ++arcs) {
				if (r % 10 > 0) {
					b.insert(0, getApostrophedSymbol(r % 10, arcs));
				}
				r /= 10;
			}
			b.append(toStringSimple(val % 1000, mode));
			return b.toString();
		}
	}

	/**
	 * Returns the apostrophed symbol for a basic value
	 * 
	 * @param val the basic input value for apostrophed compounds
	 * @param arcs number of arcs/circles to generate
	 * @return apostrophed symbols
	 */
	private static String getApostrophedSymbol(long val, int arcs) {
		if (val > 9) {
			throw new IllegalArgumentException("Invalid value for val=" + val + " > 9");
		} else if (val < 1) {
			throw new IllegalArgumentException("Invalid value for val=" + val + " < 1");
		} else if (val == 5 || val == 1) {
			char[] buf;
			if (val == 5) {
				++arcs;
				buf = new char[arcs + 1];
			} else {
				buf = new char[arcs * 2 + 1];
			}
			Arrays.fill(buf, 0, buf.length - arcs - 1, RomanChar.C.getChar());
			buf[buf.length - arcs - 1] = RomanChar.APO.getChar();
			Arrays.fill(buf, buf.length - arcs, buf.length, RomanChar._C.getChar());
			return String.valueOf(buf);
		} else {
			return getApostrophedSymbol(val - 1, arcs) + getApostrophedSymbol(1, arcs);
		}
	}

	/**
	 * Formats a number where large values (above 5000) are represented with help of {@link RomanChar#CIF} (thousand separator)
	 * 
	 * @param val value to represent
	 * @return formatted value
	 */
	public static String toStringWithCifrao(long val, NumeralSymbolMode mode) {
		if (val < 0) {
			throw new IllegalArgumentException("Number must be non-negative: " + val);
		} else if (val < 5000) {
			return toStringSimple(val, mode);
		} else {
			StringBuilder b = new StringBuilder();
			while (val > 1000) {
				b.insert(0, toStringSimple(val % 1000, mode));
				b.insert(0, RomanChar.CIF.getChar());
				val /= 1000;
			}
			b.insert(0, toStringSimple(val, mode));
			return b.toString();
		}
	}

	/**
	 * Check if a string is a valid representation of a roman number
	 * 
	 * @param s string to check
	 * @throws ParseException if the string is not a valid representation of a roman number
	 */
	public static void check(String s, NumeralSymbolMode digitType, LargeNumeralMode largeType) throws ParseException {
		if (s == null) {
			throw new ParseException("Number must not be NULL.", -1);
		}
		Pattern p = largeType.getPattern(digitType);

		Matcher matcher = p.matcher(s);
		if (!matcher.matches()) {
			throw new ParseException("'" + s + "' is not a valid roman number character.", -1);
		}
	}

	public static long parse(String s) throws ParseException {
		return parse(s, NumeralSymbolMode.STRICT, LargeNumeralMode.SIMPLE);
	}

	/**
	 * Parses a roman number string into a number
	 * 
	 * @param s the string to parse
	 * @param numberMode acceptable number symbol mode
	 * @param largeMode acceptable mode for large numbers
	 * @return value represented by the input string
	 * @throws ParseException if the string is not a valid roman number
	 */
	public static long parse(String s, NumeralSymbolMode numberMode, LargeNumeralMode largeMode) throws ParseException {
		check(s, numberMode, largeMode);

		RomanChar[] chars = toRomanChars(s);
		long val = 0;

		// buffer for short sequences of related characters
		LinkedList<RomanChar> buf = new LinkedList<RomanChar>();
		if (chars.length > 0) {
			buf.add(chars[0]);
		}

		for (int i = 1; i < chars.length; ++i) {
			final RomanChar last = buf.peekLast();
			final RomanChar c = chars[i];
			if (c == RomanChar.CIF) {
				val += valueOf(buf, i);
				val *= 1000;
			} else {
				if (!buf.isEmpty() && last != c) {
					// char changed
					switch (c) {
						case APO:
							if (last != RomanChar.C) {
								throw new ParseException("Invalid apostroph after '" + last, 1);
							}
							break;
						case _C:
							RomanChar l = last;
							if (l != RomanChar.APO && l != RomanChar._C) {
								throw new ParseException("Invalid character " + RomanChar._C + " not following "
										+ Arrays.asList(RomanChar.APO, RomanChar._C), i);
							}
							break;
						default:
							if (c.isSymbolic()) {
								throw new ParseException("Invalid symbolic character " + c, i);
							}
							// work here: last char in buffer does not match the current one
							if (last.getVal() < c.getVal()) { // subtraction rule
								val -= valueOf(buf, i);
							} else {
								val += valueOf(buf, i);
							}
					}
				}
				buf.add(c);
			}
		}
		val += valueOf(buf, chars.length);
		return val;
	}

	/**
	 * Returns the value of a character buffer and empties the buffer
	 * 
	 * @param buf list of roman characters which should be turned into a number
	 * @param bufTailPos to position in the whole string at which the buffer <em>ends</em>
	 * @return value of the provided buffer
	 * @throws ParseException if the buffer contains characters which cannot be turned into a number
	 * @throws NullPointerException if the buffer is <code>null</code>.
	 */
	private static long valueOf(LinkedList<RomanChar> buf, int bufTailPos) throws ParseException {
		long retVal = 0;
		if (buf == null) {
			throw new NullPointerException("Roman character buffer must not be NULL");
		} else if (buf.isEmpty()) {
			log.info("Evaluating empty roman character buffer at position " + bufTailPos);
			return 0L;
		} else {
			if (buf.peekLast() == RomanChar._C) { // assumption: it's an apostrophus group: CCC|ϽϽϽ
				// process thousands characters - backwards
				retVal = valueOfApostrophusGroup(buf, bufTailPos);
			} else {
				retVal = valueOfSimpleGroup(buf, bufTailPos);
			}
		}
		buf.clear();
		return retVal;
	}

	/**
	 * Assumes the buffer contains only value-carrying symbols and turns it into a number
	 * 
	 * @param buf buffer to parse
	 * @param bufTailPos the position in whole input, at which the buffer ends.
	 * @return value of the buffer as number
	 * @throws ParseException if the buffer cannot be parsed as simple value
	 */
	private static long valueOfSimpleGroup(LinkedList<RomanChar> buf, int bufTailPos) throws ParseException {
		long retVal;
		int bufOffset = bufTailPos - buf.size();
		int pos = 0;
		final RomanChar first = buf.peekFirst();
		if (first.isSymbolic()) {
			throw new ParseException("Illegal symbolic chracter " + first, bufOffset);
		}
		for (RomanChar c : buf) {
			if (c != first) {
				throw new ParseException("Character group contains different characters.", bufOffset + pos);
			}
			++pos;
		}
		retVal = 1L * first.getVal() * pos;
		return retVal;
	}

	/**
	 * Parses a buffer as an apostrophus group
	 * 
	 * @param buf the buffer with roman symbols
	 * @param bufTailPos the position at which the buffer ends
	 * @return value of the apostrophus group in the buffer
	 * @throws ParseException if the buffer does not contain a valid apostrophus group
	 */
	private static long valueOfApostrophusGroup(LinkedList<RomanChar> buf, int bufTailPos) throws ParseException {
		long retVal;
		CountingIterator<RomanChar> iter = new CountingIterator<RomanChar>(buf.descendingIterator());
		Phase p = Phase.CLOSING;
		int closingCnt = 0;
		int openingCnt = 0;
		while (iter.hasNext()) {
			RomanChar c = iter.next();
			if (p == Phase.CLOSING) {
				if (c == RomanChar._C) {
					++closingCnt;
				} else if (c == RomanChar.APO) {
					p = Phase.OPENING;
				} else {
					throw new ParseException("Invalid character " + c + " in apostrophus group - expecting " + RomanChar._C
							+ " or " + RomanChar.APO, bufTailPos - iter.getNextCalls());
				}
			} else if (p == Phase.OPENING) {
				if (c == RomanChar.C) {
					++openingCnt;
				} else {
					throw new ParseException("Invalid character " + c + " in apostrophus group - expecting " + RomanChar.C,
							bufTailPos - iter.getNextCalls());
				}
			} else {
				throw new IllegalArgumentException("Unknown apostrophus parsing phase " + p);
			}
		}
		if (openingCnt > 0 && closingCnt != openingCnt) {
			throw new ParseException("Number of opening/closing characters in apostrophus group does not match (" + openingCnt
					+ "/" + closingCnt + ")", bufTailPos - iter.getNextCalls());
		}
		retVal = pow(10L, closingCnt) * (openingCnt == 0 ? 50 : 100);
		return retVal;
	}

	/**
	 * Simple implementation of the power function
	 * 
	 * @param base base of the function
	 * @param exp exponent
	 * @return the value of <code>base</code> to the power of <code>exp</code>.
	 */
	private static long pow(long base, int exp) {
		long x = 1;
		for (int i = 0; i < exp; ++i) {
			x *= base;
		}
		return x;
	}

	/**
	 * Returns an array of roman characters represented by a string
	 * 
	 * @param s string with a roman number
	 * @return array of roman number symbols.
	 */
	private static RomanChar[] toRomanChars(String s) {
		char[] chars = s.replaceAll("\\s", "").toUpperCase().toCharArray();
		RomanChar[] romanChars = new RomanChar[chars.length];
		for (int i = 0; i < chars.length; ++i) {
			romanChars[i] = RomanChar.byChar(chars[i]);
			if (romanChars[i] == null) {
				throw new IllegalArgumentException("Invalid character '" + chars[i] + "'");
			}
		}
		return romanChars;
	}

	public static void main(String[] args) {
		try {
			check("I$", NumeralSymbolMode.STRICT, LargeNumeralMode.SIMPLE);
		} catch (ParseException e) {
			log.log(Level.SEVERE, "Error at position " + e.getErrorOffset(), e);
		}
	}

}
