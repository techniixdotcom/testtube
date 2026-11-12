//! Fast first-match search for Java regexes over big texts (YouTube's player file, HTML pages).
//!
//! Java's regex engine tries a pattern at every position of a multi-MB text; Rust's `regex`
//! crate uses literal prefilters and a linear-time engine and is 10-100x faster on the
//! extractor's patterns. It can't run Java's dialect exactly (backreferences, lookarounds,
//! possessive quantifiers, ASCII-only classes), so this module runs a *relaxed* version of the
//! Java pattern: one that matches at every position where the Java pattern can match, and maybe
//! at a few more. Its first match is therefore never after Java's first match, so Java can
//! finish with `matcher.find(start)` from there and get exactly the result a full Java scan
//! would. If the relaxed pattern doesn't match at all, neither can the Java one.
//!
//! Anything this translator isn't sure about is reported as unsupported and Java scans as before.

use std::collections::HashMap;

use regex::{Regex, RegexBuilder};

// java.util.regex.Pattern flags
const UNIX_LINES: i32 = 0x01;
const CASE_INSENSITIVE: i32 = 0x02;
const COMMENTS: i32 = 0x04;
const MULTILINE: i32 = 0x08;
const LITERAL: i32 = 0x10;
const DOTALL: i32 = 0x20;
const UNICODE_CASE: i32 = 0x40;
const CANON_EQ: i32 = 0x80;
const UNICODE_CHARACTER_CLASS: i32 = 0x100;
const KNOWN_FLAGS: i32 = UNIX_LINES | CASE_INSENSITIVE | COMMENTS | MULTILINE | LITERAL | DOTALL
    | UNICODE_CASE | CANON_EQ | UNICODE_CHARACTER_CLASS;

/// Matches any text, used in place of a backreference.
const ANY_TEXT: &str = "(?s:.*?)";
/// Java's ASCII \s set.
const ASCII_SPACE: &str = r"\t\n\x0B\x0C\r ";
/// Every non-ASCII character, as a class range.
const NON_ASCII: &str = r"\x{80}-\x{10FFFF}";

/// Java's ASCII \w, \d or \s set, as class contents.
fn ascii_set(class: char) -> &'static str {
    match class {
        'w' => "0-9A-Za-z_",
        'd' => "0-9",
        _ => ASCII_SPACE,
    }
}

/// A Rust pattern that matches wherever the Java pattern can, or None if not sure.
pub fn relax(java: &str, flags: i32) -> Option<String> {
    // case-insensitive would need Unicode case tables (about 1 MB of library): Java does those
    if flags & !KNOWN_FLAGS != 0 || flags & (COMMENTS | CANON_EQ | CASE_INSENSITIVE) != 0 {
        return None;
    }
    let mut out = String::with_capacity(java.len() + 16);
    if flags & DOTALL != 0 {
        out.push_str("(?s)");
    }
    if flags & LITERAL != 0 {
        out.push_str(&regex::escape(java));
        return Some(out);
    }
    let chars: Vec<char> = java.chars().collect();
    let mut i = 0;
    Translator { chars: &chars, out: &mut out }.sequence(&mut i, false)?;
    if i != chars.len() {
        return None;
    }
    Some(out)
}

struct Translator<'a> {
    chars: &'a [char],
    out: &'a mut String,
}

impl Translator<'_> {
    /// Translates until the end, or until the `)` closing the current group when `in_group`.
    fn sequence(&mut self, i: &mut usize, in_group: bool) -> Option<()> {
        while *i < self.chars.len() {
            let c = self.chars[*i];
            match c {
                '\\' => self.escape(i)?,
                '[' => self.class(i)?,
                '(' => self.group(i)?,
                ')' => {
                    if !in_group {
                        return None;
                    }
                    return Some(());
                }
                // anchors become "anywhere": a superset of any zero-width assertion
                '^' | '$' => *i += 1,
                '*' | '+' | '?' => {
                    self.out.push(c);
                    *i += 1;
                    self.quantifier_suffix(i);
                }
                '{' => {
                    // a quantifier {n}, {n,}, {n,m}; Java rejects any other bare '{'
                    let close = (*i..self.chars.len()).find(|&j| self.chars[j] == '}')?;
                    let body: String = self.chars[*i + 1..close].iter().collect();
                    if body.is_empty() || !body.chars().all(|c| c.is_ascii_digit() || c == ',') {
                        return None;
                    }
                    self.out.push('{');
                    self.out.push_str(&body);
                    self.out.push('}');
                    *i = close + 1;
                    self.quantifier_suffix(i);
                }
                _ => {
                    self.out.push(c);
                    *i += 1;
                }
            }
        }
        if in_group {
            None
        } else {
            Some(())
        }
    }

    /// After a quantifier: keep lazy `?`, drop possessive `+` (a greedy one matches a superset).
    fn quantifier_suffix(&mut self, i: &mut usize) {
        match self.chars.get(*i) {
            Some('?') => {
                self.out.push('?');
                *i += 1;
            }
            Some('+') => *i += 1,
            _ => {}
        }
    }

    fn group(&mut self, i: &mut usize) -> Option<()> {
        let rest = &self.chars[*i..];
        let starts = |p: &str| rest.len() >= p.len() && rest.iter().zip(p.chars()).all(|(a, b)| *a == b);
        if starts("(?=") || starts("(?!") || starts("(?<=") || starts("(?<!") {
            // lookaround: dropped, an empty pattern matches wherever it could
            *i += if starts("(?<") { 4 } else { 3 };
            let mut discard = String::new();
            Translator { chars: self.chars, out: &mut discard }.sequence(i, true)?;
            *i += 1; // ')'
            return Some(());
        }
        if starts("(?>") {
            // atomic group: as an ordinary one it matches a superset
            self.out.push_str("(?:");
            *i += 3;
        } else if starts("(?<") {
            // named group (?<name>...)
            let close = (*i..self.chars.len()).find(|&j| self.chars[j] == '>')?;
            let name: String = self.chars[*i + 3..close].iter().collect();
            if name.is_empty() || !name.chars().all(|c| c.is_ascii_alphanumeric()) {
                return None;
            }
            self.out.push_str("(?<");
            self.out.push_str(&name);
            self.out.push('>');
            *i = close + 1;
        } else if starts("(?") {
            // inline flags (?i) (?s:...) (?-i): only the ones that mean the same in both
            let mut j = *i + 2;
            let mut flags = String::new();
            while j < self.chars.len() && (self.chars[j].is_ascii_alphabetic() || self.chars[j] == '-') {
                flags.push(self.chars[j]);
                j += 1;
            }
            if !flags.chars().all(|c| matches!(c, 's' | 'm' | '-')) {
                return None;
            }
            match self.chars.get(j) {
                Some(':') => {
                    self.out.push_str("(?");
                    self.out.push_str(&flags);
                    self.out.push(':');
                    *i = j + 1;
                }
                Some(')') => {
                    // (?i) for the rest of the pattern; 'm' only affects anchors, which are gone
                    self.out.push_str("(?");
                    self.out.push_str(&flags);
                    self.out.push(')');
                    *i = j + 1;
                    return Some(());
                }
                _ => return None,
            }
        } else {
            self.out.push('(');
            *i += 1;
        }
        self.sequence(i, true)?;
        self.out.push(')');
        *i += 1;
        // a quantifier after the group is handled by the main loop
        Some(())
    }

    fn escape(&mut self, i: &mut usize) -> Option<()> {
        let next = *self.chars.get(*i + 1)?;
        match next {
            '1'..='9' => {
                // backreference: any text
                *i += 2;
                while self.chars.get(*i).is_some_and(|c| c.is_ascii_digit()) {
                    *i += 1;
                }
                self.out.push_str(ANY_TEXT);
            }
            'k' => {
                // \k<name>
                if self.chars.get(*i + 2) != Some(&'<') {
                    return None;
                }
                let close = (*i..self.chars.len()).find(|&j| self.chars[j] == '>')?;
                *i = close + 1;
                self.out.push_str(ANY_TEXT);
            }
            'Q' => {
                let start = *i + 2;
                let mut end = start;
                while end < self.chars.len() && !(self.chars[end] == '\\' && self.chars.get(end + 1) == Some(&'E')) {
                    end += 1;
                }
                let literal: String = self.chars[start..end].iter().collect();
                self.out.push_str(&regex::escape(&literal));
                *i = if end < self.chars.len() { end + 2 } else { end };
            }
            // zero-width assertions: anywhere
            'b' | 'B' | 'A' | 'z' | 'Z' | 'G' => *i += 2,
            // Java's \W, \D, \S can be larger than Rust's Unicode ones: use the ASCII complements
            'W' => {
                self.out.push_str("[^0-9A-Za-z_]");
                *i += 2;
            }
            'D' => {
                self.out.push_str("[^0-9]");
                *i += 2;
            }
            'S' => {
                self.out.push_str("[^");
                self.out.push_str(ASCII_SPACE);
                self.out.push(']');
                *i += 2;
            }
            // \w \d \s: Java's ASCII set (desktop) or a Unicode one (Android's ICU). "That ASCII
            // set or any non-ASCII character" covers both, without Unicode tables in the library.
            'w' | 'd' | 's' => {
                self.out.push('[');
                self.out.push_str(ascii_set(next));
                self.out.push_str(NON_ASCII);
                self.out.push(']');
                *i += 2;
            }
            't' | 'n' | 'r' | 'f' | 'a' => {
                self.out.push('\\');
                self.out.push(next);
                *i += 2;
            }
            'x' | 'u' | 'p' | 'P' => {
                // \xhh \x{h..} \uhhhh: same syntax. \p{..} needs Unicode tables this build doesn't
                // have, so it fails to compile and Java does it
                self.out.push('\\');
                self.out.push(next);
                *i += 2;
            }
            c if c.is_ascii_punctuation() => {
                self.out.push('\\');
                self.out.push(c);
                *i += 2;
            }
            // \0 octal, \c control, \e, \h, \v, \R, \X, ...: not sure, let Java do it
            _ => return None,
        }
        Some(())
    }

    /// A character class. Positive classes keep Unicode \w \d \s (supersets of Java's); negated
    /// ones use Java's ASCII sets so that what they exclude is never more than Java excludes.
    fn class(&mut self, i: &mut usize) -> Option<()> {
        *i += 1;
        let negated = self.chars.get(*i) == Some(&'^');
        self.out.push('[');
        if negated {
            self.out.push('^');
            *i += 1;
        }
        let mut first = true;
        while *i < self.chars.len() {
            let c = self.chars[*i];
            match c {
                ']' if !first => {
                    self.out.push(']');
                    *i += 1;
                    return Some(());
                }
                ']' => return None, // "[]" isn't a valid Java class start either way
                '[' => {
                    if negated {
                        return None;
                    }
                    self.class(i)?;
                }
                '&' if self.chars.get(*i + 1) == Some(&'&') => return None,
                '\\' => {
                    let next = *self.chars.get(*i + 1)?;
                    match next {
                        'w' | 'd' | 's' if negated => {
                            self.out.push_str(ascii_set(next));
                            *i += 2;
                        }
                        'w' | 'd' | 's' => {
                            self.out.push_str(ascii_set(next));
                            self.out.push_str(NON_ASCII);
                            *i += 2;
                        }
                        'W' | 'D' | 'S' if negated => return None,
                        'W' => {
                            self.out.push_str("[^0-9A-Za-z_]");
                            *i += 2;
                        }
                        'D' => {
                            self.out.push_str("[^0-9]");
                            *i += 2;
                        }
                        'S' => {
                            self.out.push_str("[^");
                            self.out.push_str(ASCII_SPACE);
                            self.out.push(']');
                            *i += 2;
                        }
                        't' | 'n' | 'r' | 'f' | 'a' | 'x' | 'u' | 'p' | 'P' => {
                            self.out.push('\\');
                            self.out.push(next);
                            *i += 2;
                        }
                        c if c.is_ascii_punctuation() => {
                            self.out.push('\\');
                            self.out.push(c);
                            *i += 2;
                        }
                        _ => return None,
                    }
                }
                // Rust treats these specially inside classes, Java doesn't
                '-' if self.chars.get(*i + 1) == Some(&'-') => return None,
                '~' if self.chars.get(*i + 1) == Some(&'~') => return None,
                _ => {
                    self.out.push(c);
                    *i += 1;
                }
            }
            first = false;
        }
        None
    }
}

/// Texts and compiled patterns, so a multi-MB text crosses JNI once and each pattern compiles once.
pub struct Scanner {
    texts: Vec<Text>,
    next_id: u64,
    patterns: HashMap<(String, i32), Option<Regex>>,
}

struct Text {
    id: u64,
    text: String,
    ascii: bool,
}

/// Results of [`Scanner::find`], as given to Java.
pub const NO_MATCH: i64 = -1;
pub const UNSUPPORTED: i64 = -2;
pub const TEXT_DROPPED: i64 = -3;

const MAX_TEXTS: usize = 2;
const MAX_PATTERNS: usize = 128;

impl Default for Scanner {
    fn default() -> Self {
        Self::new()
    }
}

impl Scanner {
    pub fn new() -> Self {
        Self { texts: Vec::new(), next_id: 1, patterns: HashMap::new() }
    }

    pub fn load(&mut self, text: String) -> u64 {
        if self.texts.len() == MAX_TEXTS {
            self.texts.remove(0);
        }
        let id = self.next_id;
        self.next_id += 1;
        let ascii = text.is_ascii();
        self.texts.push(Text { id, text, ascii });
        id
    }

    /// UTF-16 index of the first position at or after `from` (a UTF-16 index) where the Java
    /// pattern can match, [`NO_MATCH`] if it can't match anywhere there, [`UNSUPPORTED`] if
    /// Java has to look itself, or [`TEXT_DROPPED`] if the text has to be loaded again.
    pub fn find(&mut self, text_id: u64, pattern: &str, flags: i32, from: usize) -> i64 {
        let key = (pattern.to_string(), flags);
        if !self.patterns.contains_key(&key) {
            if self.patterns.len() >= MAX_PATTERNS {
                self.patterns.clear();
            }
            let compiled = relax(pattern, flags).and_then(|relaxed| {
                RegexBuilder::new(&relaxed).size_limit(32 << 20).dfa_size_limit(32 << 20).build().ok()
            });
            self.patterns.insert(key.clone(), compiled);
        }
        let Some(regex) = self.patterns.get(&key).and_then(|r| r.as_ref()) else {
            return UNSUPPORTED;
        };
        let Some(text) = self.texts.iter().find(|t| t.id == text_id) else {
            return TEXT_DROPPED;
        };
        let Some(byte_from) = utf16_to_byte(text, from) else {
            return UNSUPPORTED;
        };
        match regex.find_at(&text.text, byte_from) {
            Some(m) => byte_to_utf16(text, m.start()) as i64,
            None => NO_MATCH,
        }
    }

    pub fn clear(&mut self) {
        self.texts.clear();
        self.patterns.clear();
    }
}

fn utf16_to_byte(text: &Text, utf16: usize) -> Option<usize> {
    if text.ascii {
        return (utf16 <= text.text.len()).then_some(utf16);
    }
    let mut units = 0;
    for (byte, c) in text.text.char_indices() {
        if units >= utf16 {
            // inside a surrogate pair can't be expressed in UTF-8
            return (units == utf16).then_some(byte);
        }
        units += c.len_utf16();
    }
    (units == utf16).then_some(text.text.len())
}

fn byte_to_utf16(text: &Text, byte: usize) -> usize {
    if text.ascii {
        return byte;
    }
    text.text[..byte].chars().map(char::len_utf16).sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relaxes_java_only_syntax() {
        assert_eq!(relax(r"([\w$]+)=function\((\w+)\)\{\2=\2\.split", 0).unwrap(),
                   r"([0-9A-Za-z_\x{80}-\x{10FFFF}$]+)=function\(([0-9A-Za-z_\x{80}-\x{10FFFF}]+)\)\{(?s:.*?)=(?s:.*?)\.split");
        assert_eq!(relax(r"\bfoo(?=bar)baz", 0).unwrap(), "foobaz");
        assert_eq!(relax(r"a++b*+c?+", 0).unwrap(), "a+b*c?");
        assert_eq!(relax(r"(?>ab)", 0).unwrap(), "(?:ab)");
        assert_eq!(relax(r"\Qa.b\E", 0).unwrap(), r"a\.b");
        assert_eq!(relax(r"^start$", 0).unwrap(), "start");
        assert_eq!(relax(r"[^\w$]", 0).unwrap(), "[^0-9A-Za-z_$]");
        assert_eq!(relax(r"x\Wy", 0).unwrap(), "x[^0-9A-Za-z_]y");
        assert_eq!(relax("a.b", DOTALL).unwrap(), "(?s)a.b");
        assert_eq!(relax("a.b", LITERAL).unwrap(), r"a\.b");
        assert!(relax(r"\h", 0).is_none());
        assert!(relax(r"[a-z&&[^m]]", 0).is_none());
        assert!(relax("a", COMMENTS).is_none());
        assert!(relax("(?x)a", 0).is_none());
        assert!(relax("a", CASE_INSENSITIVE).is_none());
        assert!(relax("(?i)a", 0).is_none());
        assert_eq!(relax(r"\d+", 0).unwrap(), r"[0-9\x{80}-\x{10FFFF}]+");
    }

    #[test]
    fn finds_the_first_possible_match_in_utf16() {
        let mut s = Scanner::new();
        let id = s.load("héllo wörld, x=function(a){a=a.split(\"\");".to_string());
        // 'é' and 'ö' are one UTF-16 unit each but two UTF-8 bytes
        assert_eq!(s.find(id, "w.rld", 0, 0), 6);
        assert_eq!(s.find(id, r"([\w$]+)=function\((\w+)\)\{\2=\2\.split", 0, 0), 13);
        assert_eq!(s.find(id, "nothere", 0, 0), NO_MATCH);
        assert_eq!(s.find(id, r"\h", 0, 0), UNSUPPORTED);
        assert_eq!(s.find(id + 99, "x", 0, 0), TEXT_DROPPED);
        assert_eq!(s.find(id, "l", 0, 4), 9);
    }
}
