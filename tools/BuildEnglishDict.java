import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds app/src/main/assets/dict/en.dict from two open word-frequency lists:
 *   en_subs.txt  OpenSubtitles 2018 (hermitdave/FrequencyWords, CC BY-SA 4.0)  "word count"
 *   en_web.txt   Google Web Trillion Word unigrams (norvig.com/ngrams)           "word\tcount"
 *
 * Usage: java tools/BuildEnglishDict.java tools/data app/src/main/assets/dict/en.dict
 *
 * Binary format (big endian), see EnglishDictionary.kt:
 *   magic "AIKBEN01", int count,
 *   int[count] entry offsets (relative to data start), sorted by key,
 *   data: per entry  u8 freq, u8 keyLen, key bytes, u8 dispLen, display bytes
 * key = lowercase a-z only (apostrophes removed) so "dont" finds "don't".
 */
public class BuildEnglishDict {
    static final double SUBS_WEIGHT = 0.65, WEB_WEIGHT = 0.35;

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

        // display form overrides and contractions (key -> display, zipf)
        Map<String, String> display = new HashMap<>();
        display.put("i", "I");
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
            {"hes", "he's", "5.8"}, {"shes", "she's", "5.6"}, {"its_", "it's", "6.4"},
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
        // Common misspellings: the corpus contains them often enough to look like words, so drop
        // the misspelled form and map its key to the right word (handles edit distance > 1 too).
        int fixed = 0;
        for (String pair : MISSPELLINGS.trim().split("\\s*,\\s*")) {
            String[] p = pair.trim().split("\\s*=\\s*");
            if (p.length != 2) continue;
            String wrong = p[0], right = p[1];
            entries.removeIf(en -> en[0].equals(wrong) && en[1].equals(wrong));
            String rightKey = right.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            Double z = score.get(rightKey);
            if (z == null) for (String[] c : contractions) if (c[0].replace("_", "").equals(rightKey)) z = Double.parseDouble(c[2]);
            z = Math.max(z == null ? 0 : z, 5.0); // a known fix must outrank edit-distance guesses
            entries.add(new String[]{wrong, right, Double.toString(z)});
            fixed++;
        }
        System.out.println("misspellings mapped: " + fixed);
        entries.sort(Comparator.comparing((String[] a) -> a[0]).thenComparing(a -> -Double.parseDouble(a[2])));

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            String[] en = entries.get(i);
            offsets[i] = data.size();
            int f = (int) Math.round(Double.parseDouble(en[2]) * 30);
            data.write(Math.max(1, Math.min(255, f)));
            byte[] k = en[0].getBytes(StandardCharsets.UTF_8);
            byte[] d = en[1].getBytes(StandardCharsets.UTF_8);
            data.write(k.length); data.write(k);
            data.write(d.length); data.write(d);
        }
        Files.createDirectories(out.getParent());
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            o.write("AIKBEN01".getBytes(StandardCharsets.US_ASCII));
            o.writeInt(entries.size());
            for (int off : offsets) o.writeInt(off);
            data.writeTo(o);
        }
        System.out.printf("wrote %d entries, %d bytes -> %s%n", entries.size(), Files.size(out), out);
    }

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
