import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds app/src/main/assets/dict/en.dict from open word-frequency & bigram corpora:
 *   en_subs.txt     OpenSubtitles 2018 (hermitdave/FrequencyWords, CC BY-SA 4.0)  "word count"
 *   en_web.txt      Google Web Trillion Word unigrams (norvig.com/ngrams)         "word\tcount"
 *   en_bigrams.txt  Google Web Trillion Word 2-grams (norvig.com/ngrams)          "w1 w2\tcount"
 *
 * Usage: java tools/BuildEnglishDict.java tools/data app/src/main/assets/dict/en.dict
 *
 * Binary format AIKBEN02 (big endian), see EnglishDictionary.kt:
 *   magic "AIKBEN02" (8B)
 *   int unigramCount
 *   int bigramHeadCount
 *   int bigramDataStart          (byte offset from start of file to bigram section)
 *   int[unigramCount] entryOffsets (relative to unigramDataStart, sorted by key)
 *   unigramData: per entry  u8 freq, u8 keyLen, key bytes, u8 dispLen, display bytes
 *   [padding to 4-byte boundary -> bigramDataStart]
 *   int[bigramHeadCount] headUnigramIdx   (sorted ascending by unigram index)
 *   int[bigramHeadCount + 1] edgeStart    (edge index into edges[])
 *   int[edgeCount] edges                  ((targetUnigramIdx << 8) | (score & 0xFF))
 *
 * key = lowercase a-z only (apostrophes removed) so "dont" finds "don't".
 */
public class BuildEnglishDict {
    static final double SUBS_WEIGHT = 0.65, WEB_WEIGHT = 0.35;
    static final int MAX_BIGRAMS_PER_HEAD = 32;

    public static void main(String[] args) throws IOException {
        Path dataDir = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Map<String, Double> subs = readZipf(dataDir.resolve("en_subs.txt"), 10);
        Map<String, Double> web = readZipf(dataDir.resolve("en_web.txt"), 20000);
        System.out.printf("subs=%d web=%d%n", subs.size(), web.size());

        Map<String, Double> score = new HashMap<>();
        Set<String> all = new HashSet<>(subs.keySet());
        all.addAll(web.keySet());
        for (String w : all) {
            if (!w.matches("[a-z]+")) continue;
            if (w.length() == 1 && !w.equals("a") && !w.equals("i")) continue;
            Double s = subs.get(w), g = web.get(w);
            double z;
            if (s != null && g != null) z = SUBS_WEIGHT * s + WEB_WEIGHT * g;
            else if (s != null) z = s - 0.8;          // conversational but unseen on the web
            else z = g - 1.2;                          // web-only: often names, brands, typos
            if (z < 0.6) continue;
            score.put(w, z);
        }

        // Unambiguous proper nouns & camelCase brands (never common lowercase nouns)
        Map<String, String> display = new HashMap<>();
        display.put("i", "I");
        for (String p : PROPER_NOUNS.trim().split("\\s*,\\s*")) {
            if (p.isEmpty()) continue;
            String low = p.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            display.put(low, p);
            score.put(low, Math.max(score.getOrDefault(low, 0.0), 5.1));
        }

        String[][] contractions = {
            {"im", "I'm", "6.6"}, {"ive", "I've", "5.6"}, {"ill", "I'll", "5.9"}, {"id", "I'd", "5.5"},
            {"dont", "don't", "6.6"}, {"doesnt", "doesn't", "5.8"}, {"didnt", "didn't", "5.9"},
            {"cant", "can't", "6.0"}, {"couldnt", "couldn't", "5.3"}, {"wouldnt", "wouldn't", "5.2"},
            {"shouldnt", "shouldn't", "4.8"}, {"wont", "won't", "5.6"}, {"isnt", "isn't", "5.5"},
            {"arent", "aren't", "5.1"}, {"wasnt", "wasn't", "5.4"}, {"werent", "weren't", "4.8"},
            {"havent", "haven't", "5.3"}, {"hasnt", "hasn't", "4.8"}, {"hadnt", "hadn't", "4.5"},
            {"youre", "you're", "6.1"}, {"youve", "you've", "5.4"}, {"youll", "you'll", "5.3"},
            {"youd", "you'd", "5.0"}, {"theyre", "they're", "5.6"}, {"theyve", "they've", "4.8"},
            {"theyll", "they'll", "4.7"}, {"weve", "we've", "5.1"}, {"were_", "we're", "5.7"},
            {"well_", "we'll", "5.2"}, {"thats", "that's", "6.2"}, {"whats", "what's", "5.9"},
            {"lets", "let's", "5.8"}, {"theres", "there's", "5.6"}, {"heres", "here's", "5.1"},
            {"hes", "he's", "5.8"}, {"shes", "she's", "5.6"}, {"its_", "it's", "6.3"},
            {"whos", "who's", "5.1"}, {"wheres", "where's", "4.9"}, {"hows", "how's", "4.6"},
            {"aint", "ain't", "4.6"}, {"yall", "y'all", "4.3"}, {"mustnt", "mustn't", "3.8"},
            {"neednt", "needn't", "3.5"}, {"itll", "it'll", "4.8"}, {"thatll", "that'll", "4.4"},
        };

        // Entries are keyed by (key, display); several displays may share a key ("its"/"it's").
        List<String[]> entries = new ArrayList<>();
        for (Map.Entry<String, Double> e : score.entrySet()) {
            String w = e.getKey();
            entries.add(new String[]{w, display.getOrDefault(w, w), Double.toString(e.getValue())});
        }
        for (String[] c : contractions) {
            String key = c[0].replace("_", "");
            entries.add(new String[]{key, c[1], c[2]});
        }

        // Non-destructive chat shorthand suggestions (offered in candidate strip without overwriting valid slang)
        String[][] chatShortcuts = {
            {"u", "you", "5.8"}, {"ur", "your", "5.4"}, {"pls", "please", "5.6"}, {"plz", "please", "5.5"},
            {"thx", "thanks", "5.6"}, {"tmr", "tomorrow", "5.2"}, {"tmrw", "tomorrow", "5.3"},
            {"btw", "by the way", "5.0"}, {"idk", "I don't know", "5.0"}, {"omw", "on my way", "4.9"},
            {"imo", "in my opinion", "4.8"}, {"tbh", "to be honest", "4.9"}, {"nvm", "never mind", "4.8"},
        };
        for (String[] s : chatShortcuts) {
            entries.add(new String[]{s[0], s[1], s[2]});
        }

        // Common misspellings: drop the misspelled form and map its key to the right word
        int fixed = 0;
        for (String pair : MISSPELLINGS.trim().split("\\s*,\\s*")) {
            String[] p = pair.trim().split("\\s*=\\s*");
            if (p.length != 2) continue;
            String wrong = p[0], right = p[1];
            entries.removeIf(en -> en[0].equals(wrong) && en[1].equals(wrong));
            String rightKey = right.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            Double z = score.get(rightKey);
            if (z == null) for (String[] c : contractions) if (c[0].replace("_", "").equals(rightKey)) z = Double.parseDouble(c[2]);
            z = Math.max(z == null ? 0 : z, 5.0);
            entries.add(new String[]{wrong, right, Double.toString(z)});
            fixed++;
        }
        System.out.println("misspellings mapped: " + fixed);
        entries.sort(Comparator.comparing((String[] a) -> a[0]).thenComparing(a -> -Double.parseDouble(a[2])));

        // Map canonical word form (display.toLowerCase, e.g. "don't", "i'm", "its", "it's", "monday") -> unigram index
        Map<String, Integer> canonicalIdx = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String key = entries.get(i)[0];
            String disp = entries.get(i)[1];
            String normDisp = disp.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (key.equals(normDisp)) {
                canonicalIdx.putIfAbsent(disp.toLowerCase(Locale.ROOT), i);
            }
        }

        // Build bigram adjacency list: headIdx -> (targetIdx -> rawCount)
        Path bigramPath = dataDir.resolve("en_bigrams.txt");
        Map<Integer, Map<Integer, Double>> rawBigrams = new HashMap<>();
        if (Files.exists(bigramPath)) {
            try (BufferedReader r = Files.newBufferedReader(bigramPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    int sp = line.indexOf(' ');
                    if (sp <= 0 || sp >= tab) continue;
                    String w1 = line.substring(0, sp).toLowerCase(Locale.ROOT);
                    String w2 = line.substring(sp + 1, tab).toLowerCase(Locale.ROOT);
                    if (w1.equals(w2)) continue;
                    if (w2.length() == 1 && !w2.equals("a") && !w2.equals("i")) continue;
                    // Require w2 to be genuinely used in human conversation (filters web footer/eBay spam)
                    String w2Alpha = w2.replaceAll("[^a-z]", "");
                    Double subZ = subs.get(w2Alpha);
                    if (subZ == null || subZ < 3.1) continue;
                    Integer idx1 = canonicalIdx.get(w1);
                    Integer idx2 = canonicalIdx.get(w2);
                    if (idx1 == null || idx2 == null) continue;
                    double cnt;
                    try { cnt = Double.parseDouble(line.substring(tab + 1).trim()); } catch (NumberFormatException e) { continue; }
                    rawBigrams.computeIfAbsent(idx1, k -> new HashMap<>()).merge(idx2, cnt, Double::sum);
                }
            }
        }

        // Inject conversational dialogue & contraction bigrams
        addConversationalBigrams(rawBigrams, canonicalIdx);

        // Score and prune bigrams per head word
        List<Integer> heads = new ArrayList<>(rawBigrams.keySet());
        Collections.sort(heads);
        List<Integer> keptHeads = new ArrayList<>();
        List<int[]> keptEdges = new ArrayList<>();
        int totalEdges = 0;

        for (int h : heads) {
            Map<Integer, Double> nextMap = rawBigrams.get(h);
            double sum = 0;
            for (double c : nextMap.values()) sum += c;
            if (sum <= 0) continue;
            List<long[]> scored = new ArrayList<>();
            for (Map.Entry<Integer, Double> e : nextMap.entrySet()) {
                int targetIdx = e.getKey();
                double prob = e.getValue() / sum;
                double logP = Math.log10(Math.max(prob, 1e-5));
                String tAlpha = entries.get(targetIdx)[0];
                double subZ = subs.getOrDefault(tAlpha, 4.2);
                double convBonus = Math.max(-0.6, Math.min(0.55, 0.22 * (subZ - 4.5)));
                int bScore = (int) Math.round((logP + 3.15) * 66.0 + convBonus * 38.0);
                if (bScore < 18) continue;
                bScore = Math.min(255, bScore);
                scored.add(new long[]{targetIdx, bScore});
            }
            if (scored.isEmpty()) continue;
            scored.sort((a, b) -> Long.compare(b[1], a[1]));
            int n = Math.min(MAX_BIGRAMS_PER_HEAD, scored.size());
            int[] packed = new int[n];
            for (int i = 0; i < n; i++) {
                int tIdx = (int) scored.get(i)[0];
                int sc = (int) scored.get(i)[1];
                packed[i] = (tIdx << 8) | (sc & 0xFF);
            }
            keptHeads.add(h);
            keptEdges.add(packed);
            totalEdges += n;
        }
        System.out.printf("bigrams: %d head words, %d edges%n", keptHeads.size(), totalEdges);

        ByteArrayOutputStream unigramBytes = new ByteArrayOutputStream();
        int[] offsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String[] en = entries.get(i);
            offsets[i] = unigramBytes.size();
            int f = (int) Math.round(Double.parseDouble(en[2]) * 30);
            unigramBytes.write(Math.max(1, Math.min(255, f)));
            byte[] k = en[0].getBytes(StandardCharsets.UTF_8);
            byte[] d = en[1].getBytes(StandardCharsets.UTF_8);
            unigramBytes.write(k.length); unigramBytes.write(k);
            unigramBytes.write(d.length); unigramBytes.write(d);
        }

        int headerBytes = 8 + 4 + 4 + 4 + entries.size() * 4;
        int rawUnigramEnd = headerBytes + unigramBytes.size();
        int pad = (4 - (rawUnigramEnd & 3)) & 3;
        int bigramDataStart = rawUnigramEnd + pad;

        Files.createDirectories(out.getParent());
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            o.write("AIKBEN02".getBytes(StandardCharsets.US_ASCII));
            o.writeInt(entries.size());
            o.writeInt(keptHeads.size());
            o.writeInt(bigramDataStart);
            for (int off : offsets) o.writeInt(off);
            unigramBytes.writeTo(o);
            for (int i = 0; i < pad; i++) o.writeByte(0);

            for (int h : keptHeads) o.writeInt(h);
            int curEdge = 0;
            for (int[] arr : keptEdges) {
                o.writeInt(curEdge);
                curEdge += arr.length;
            }
            o.writeInt(curEdge);
            for (int[] arr : keptEdges) {
                for (int edge : arr) o.writeInt(edge);
            }
        }
        System.out.printf("wrote %d entries + %d bigram edges, %d bytes -> %s%n",
            entries.size(), totalEdges, Files.size(out), out);
    }

    static void addConversationalBigrams(Map<Integer, Map<Integer, Double>> rawBigrams, Map<String, Integer> canonicalIdx) {
        for (String group : CONVERSATIONAL_BIGRAMS.trim().split("\n")) {
            String g = group.trim();
            if (g.isEmpty() || g.startsWith("#")) continue;
            String[] parts = g.split(":");
            if (parts.length != 2) continue;
            Integer h = canonicalIdx.get(parts[0].trim().toLowerCase(Locale.ROOT));
            if (h == null) continue;
            Map<Integer, Double> map = rawBigrams.computeIfAbsent(h, k -> new HashMap<>());
            double existingSum = 0;
            for (double v : map.values()) existingSum += v;
            double base = Math.max(existingSum, 2_000_000.0);
            String[] targets = parts[1].trim().split("\\s*,\\s*");
            for (int i = 0; i < targets.length; i++) {
                String t = targets[i].trim().toLowerCase(Locale.ROOT);
                Integer tIdx = canonicalIdx.get(t);
                if (tIdx == null || tIdx.equals(h)) continue;
                double weight = base * (0.30 / (1.0 + 0.45 * i));
                map.merge(tIdx, weight, Double::sum);
            }
        }
    }

    static final String PROPER_NOUNS = """
        Monday, Tuesday, Wednesday, Thursday, Friday, Saturday, Sunday,
        January, February, April, June, July, August, September, October, November, December,
        Christmas, Easter, Halloween, Thanksgiving,
        English, Chinese, Japanese, Korean, French, German, Spanish, Italian, Russian, Arabic,
        America, American, Americans, Japan, Korea, Europe, European, Canada, Canadian, Australia, Australian,
        Britain, British, India, Indian, Mexico, Mexican, Brazil, Russia, France, Germany, Italy, Spain,
        London, Paris, Tokyo, Beijing, Shanghai, Shenzhen, Singapore, California, Texas, Florida, Chicago, Boston, Seattle, York,
        Google, Android, iPhone, iPad, MacBook, AirPods, Microsoft, Linux, Ubuntu, GitHub, YouTube,
        Instagram, Facebook, WhatsApp, Telegram, WeChat, TikTok, Netflix, Spotify, OpenAI, ChatGPT,
        Kotlin, JavaScript, TypeScript, WiFi, Bluetooth, Internet
        """;

    static final String CONVERSATIONAL_BIGRAMS = """
        i: am, have, don't, think, can, will, was, want, need, know, just, would, love, hope, can't
        i'm: going, not, sorry, so, in, on, just, here, sure, happy, ready, done, glad, still, looking
        i've: been, got, never, had, seen, just, always, already, heard, made
        i'll: be, let, see, send, get, take, do, try, call, check, have, make
        i'd: like, love, rather, be, have, say, prefer
        don't: know, worry, have, think, want, need, forget, be, care, mind, understand, like
        doesn't: work, matter, make, have, seem, mean, look, hurt
        didn't: know, have, get, see, want, mean, think, say, work, realize
        can't: wait, believe, find, make, see, get, do, help, stop, remember
        won't: be, have, work, let, take, happen
        isn't: it, a, the, that, going, working, bad
        aren't: you, we, they, going, sure
        haven't: seen, been, got, had, heard, done, received
        you: are, have, can, want, know, need, don't, doing, guys, too, so, very, will
        you're: welcome, right, going, not, so, a, the, doing, free, looking, awesome
        you've: been, got, done, made, had, seen
        you'll: be, have, need, see, get, find, love
        we're: going, not, in, here, all, looking, still, ready, happy
        we'll: see, be, have, get, need, make, take
        they're: not, going, all, so, still, here, very
        it's: a, not, just, good, fine, been, going, time, too, great, okay, really, so
        its: own, way, best, first, name, new, original, current
        your: name, email, phone, help, time, own, message, family, day, work
        that's: great, good, why, what, right, fine, true, awesome, okay, nice, all, how
        what's: up, your, going, the, wrong, happening, new, that
        let's: go, see, get, do, make, talk, try, meet, start, have
        there's: no, a, nothing, something, still, always
        here's: the, a, what, my, how
        who's: going, there, that, in
        where's: the, my, your
        how: are, about, much, many, was, did, do, to, long, old, can, is
        thank: you, goodness
        thanks: for, so, again, a
        good: morning, luck, night, idea, job, news, afternoon, evening, day, to, for
        nice: to, work, day, meeting, job, one
        see: you, if, what, the, how, it
        looking: forward, for, good, at, into, to
        forward: to, it, the
        let: me, us, it, them, him, her
        take: care, a, it, your, the, time
        have: a, to, been, you, fun, any, time, no, the
        want: to, a, you, me, it, some
        going: to, on, home, well, out, back
        need: to, a, help, any, some, more, your
        would: like, be, you, love, have, never
        could: you, be, have, please, not
        should: be, have, we, I, work
        hope: you're, you, it, this, that, everything, to
        think: it's, you're, we're, I, you, so, about, that, we
        know: what, how, if, that, it's, you're, about
        in: the, a, its, my, your, our, this
        of: the, its, a, my, your, our, this
        for: the, your, a, its, you, me, my
        sorry: for, about, to, I'm, I
        talk: to, soon, later, about
        please: let, check, send, feel, find, see, don't
        feel: free, like, good, better
        free: to, time, on
        on: Monday, Tuesday, Wednesday, Thursday, Friday, Saturday, Sunday, the, my, your, time, its
        happy: birthday, to, Friday, weekend, for
        merry: Christmas
        new: York, year, one, version
        by: the, way, now, tomorrow
        a: lot, little, few, bit, good, great, new
        """;

    static final String MISSPELLINGS = """
        teh=the, hte=the, recieve=receive, recieved=received, recieving=receiving, wierd=weird, wierdo=weirdo,
        beleive=believe, belive=believe, belived=believed, definately=definitely, definatly=definitely,
        seperate=separate, occured=occurred, occuring=occurring, occurence=occurrence, untill=until, wich=which,
        thier=their, tommorow=tomorrow, tomorow=tomorrow, tommorrow=tomorrow, accomodate=accommodate,
        adress=address, alot=a lot, becuase=because, becasue=because, beacuse=because, becuae=because,
        begining=beginning, beggining=beginning, calender=calendar, cemetary=cemetery, comming=coming,
        commited=committed, concious=conscious, enviroment=environment, existance=existence, finaly=finally,
        finnally=finally, foriegn=foreign, freind=friend, goverment=government, grammer=grammar,
        happend=happened, hapened=happened, harrass=harass, independant=independent, knowlege=knowledge,
        lenght=length, libary=library, maintainance=maintenance, neccessary=necessary, necesary=necessary,
        noticable=noticeable, occassion=occasion, peice=piece, persistant=persistent, posession=possession,
        prefered=preferred, probly=probably, probaly=probably, publically=publicly, realy=really,
        reccomend=recommend, recomend=recommend, refered=referred, relevent=relevant, religous=religious,
        remeber=remember, rember=remember, responsability=responsibility, sieze=seize, succesful=successful,
        successfull=successful, suprise=surprise, suprised=surprised, tounge=tongue, truely=truly, truley=truly,
        unfortunatly=unfortunately, unfortunetly=unfortunately, wether=whether, writting=writing, writen=written,
        acheive=achieve, arguement=argument, basicly=basically, buisness=business, busness=business,
        cheif=chief, collegue=colleague, completly=completely, concensus=consensus, dissapoint=disappoint,
        dissapointed=disappointed, embarass=embarrass, embarassed=embarrassed, excercise=exercise,
        experiance=experience, familar=familiar, febuary=February, fourty=forty, gaurd=guard, guage=gauge,
        hieght=height, immediatly=immediately, interupt=interrupt, liason=liaison, millenium=millennium,
        mispell=misspell, mispelled=misspelled, neice=niece, nieghbor=neighbor, oppurtunity=opportunity,
        paralell=parallel, parliment=parliament, posible=possible, potatos=potatoes, presense=presence,
        questionaire=questionnaire, rythm=rhythm, sence=sense, shedule=schedule, speach=speech,
        strenght=strength, threshhold=threshold, tomatos=tomatoes, vaccum=vacuum, wensday=Wednesday,
        wendsday=Wednesday, whereever=wherever, yeild=yield, yesturday=yesterday, yestarday=yesterday,
        dosent=doesn't, dissappear=disappear, dilema=dilemma, eigth=eighth, enought=enough, everytime=every time,
        exellent=excellent, excelent=excellent, gratefull=grateful, greatful=grateful, intresting=interesting,
        interseting=interesting, knwo=know, konw=know, liek=like, mabye=maybe, mayby=maybe, peopel=people,
        poeple=people, pepole=people, recipt=receipt, rediculous=ridiculous, sincerly=sincerely,
        somthing=something, somehting=something, soemthing=something, stoped=stopped, thnak=thank,
        thnaks=thanks, togehter=together, waht=what, whta=what, woudl=would, wrok=work, yuor=your, yoru=your,
        adn=and, taht=that, thta=that, jsut=just, juts=just, abotu=about, coudl=could, shoudl=should,
        wnat=want, watn=want, whihc=which, wiht=with, iwth=with, ahve=have, hvae=have, frist=first,
        fisrt=first, firts=first, tihs=this, thsi=this, tiem=time, tmie=time, wokr=work, freinds=friends,
        definetly=definitely, defintely=definitely, embarrasing=embarrassing, wouldve=would've,
        couldve=could've, shouldve=should've, mightve=might've
        """;

    /** Reads "word<ws>count" lines, returns Zipf frequency log10(per-billion) for counts >= minCount. */
    static Map<String, Double> readZipf(Path p, long minCount) throws IOException {
        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) continue;
                long c;
                try { c = Long.parseLong(parts[1]); } catch (NumberFormatException e) { continue; }
                total += c;
                if (c >= minCount) counts.merge(parts[0].toLowerCase(Locale.ROOT), c, Long::sum);
            }
        }
        Map<String, Double> z = new HashMap<>();
        for (Map.Entry<String, Long> e : counts.entrySet())
            z.put(e.getKey(), Math.log10(e.getValue() * 1e9 / total));
        return z;
    }
}
