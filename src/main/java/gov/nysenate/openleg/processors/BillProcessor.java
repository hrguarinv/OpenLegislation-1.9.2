package gov.nysenate.openleg.processors;

import gov.nysenate.openleg.model.Action;
import gov.nysenate.openleg.model.Bill;
import gov.nysenate.openleg.model.Person;
import gov.nysenate.openleg.model.SOBIBlock;
import gov.nysenate.openleg.model.Vote;
import gov.nysenate.openleg.util.ChangeLogger;
import gov.nysenate.openleg.util.Storage;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.nysenate.openleg.util.UnpublishListManager;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

/**
 * Holds a collection of methods and Patterns for processing SOBI files and applying their change sets to Bills.
 * <p>
 * General Usage:
 * <pre>
 *     BillProcessor.process(new File('path/to/sobi_file.txt'), new Storage(new File('storage/directory')));
 * </pre>
 *
 * First the incoming file is broken down into independent SOBIBlocks. Each {@link SOBIBlock} operates atomically
 * on a single bill by loading it from storage, applying the block's data, and saving the changes back to storage.
 *
 * All individual blocks updating a bill are ALWAYS sent in full. Bill fields should never be updated, only replaced.
 *
 * @author GraylinKim
 *
 * @see SOBIBlock
 */
public class BillProcessor
{
    private final Logger logger = Logger.getLogger(BillProcessor.class);

    /**
     * The format required for the SobiFile name. e.g. SOBI.D130323.T065432.TXT
     */
    public static SimpleDateFormat sobiDateFormat = new SimpleDateFormat("'SOBI.D'yyMMdd'.T'HHmmss'.TXT'");

    /**
     * Date format found in SOBIBlock[4] bill event blocks. e.g. 02/04/13
     */
    protected static SimpleDateFormat eventDateFormat = new SimpleDateFormat("MM/dd/yy");

    /**
     * Date format found in SOBIBlock[V] vote memo blocks. e.g. 02/05/2013
     */
    protected static SimpleDateFormat voteDateFormat = new SimpleDateFormat("MM/dd/yyyy");

    /**
     * The expected format for the first line of the vote memo [V] block data.
     */
    public static Pattern voteHeaderPattern = Pattern.compile("Senate Vote    Bill: (.{18}) Date: (.{10}).*");

    /**
     * The expected format for recorded votes in the SOBIBlock[V] vote memo blocks; e.g. 'AYE  ADAMS          '
     */
    protected static Pattern votePattern = Pattern.compile("(.{4}) (.{1,15})");

    /**
     * The expected format for actions recorded in the bill events [4] block. e.g. 02/04/13 Event Text Here
     */
    protected static Pattern billEventPattern = Pattern.compile("([0-9]{2}/[0-9]{2}/[0-9]{2}) (.*)");

    /**
     * The expected format for SameAs [5] block data. Same as Uni A 372/S 210
     *
     * Sometimes there can be multiple same as, we just ignore the second one..
     */
    protected static Pattern sameAsPattern = Pattern.compile("Same as( Uni\\.)? ([A-Z] ?[0-9]{1,5}-?[A-Z]?)");

    /**
     * The expected format for Bill Info [1] block data.
     */
    public static Pattern billInfoPattern = Pattern.compile("(.{20})([0-9]{5}[ A-Z])(.{33})([ A-Z][0-9]{5}[ `\\-A-Z0-9])(.{8})(.*)");

    /**
     * The expected format for header lines inside bill [T] and memo [M] text data.
     */
    public static Pattern textHeaderPattern = Pattern.compile("00000\\.SO DOC ([ASC]) ([0-9R/A-Z ]{13}) ([A-Z* ]{24}) ([A-Z ]{20}) ([0-9]{4}).*");

    /**
     * Pattern for extracting the committee from matching bill events.
     */
    public static Pattern committeeEventTextPattern = Pattern.compile("(REFERRED|COMMITTED|RECOMMIT) TO (.*)");

    /**
     * Pattern for detecting calendar events in bill action lists.
     */
    public static Pattern floorEventTextPattern = Pattern.compile("(REPORT CAL|THIRD READING|RULES REPORT)");

    /**
     * Pattern for extracting the substituting bill printNo from matching bill events.
     */
    public static Pattern substituteEventTextPattern = Pattern.compile("SUBSTITUTED (FOR|BY) (.*)");

    /**
     * Used to check if a bill is unpublished before storing it
     */
    private static UnpublishListManager unpublishListManager = new UnpublishListManager();


    @SuppressWarnings("serial")
    public static class ParseError extends Exception
    {
        public ParseError(String message) { super(message); }
    }


    /**
     * Applies change sets encoded in SOBI format to bill objects in storage. Creates new Bills
     * as necessary. Does not flush changes to file system from storage.
     *
     * @param sobiFile - Must encode time stamp for the changes into the filename in SOBI.DYYMMDD.THHMMSS.TXT format.
     * @param storage
     * @throws IOException
     */
    public void process(File sobiFile, Storage storage) throws IOException
    {
        Date date = null;
        try {
            date = BillProcessor.sobiDateFormat.parse(sobiFile.getName());
        }
        catch (ParseException e) {
            logger.error("Unparseable date: "+sobiFile.getName());
            return;
        }

        // Set the context for all future changes logged.
        ChangeLogger.setContext(sobiFile, date);

        // Catch exceptions on a per-block basis so that a single error won't corrupt the whole file.
        for (SOBIBlock block : getBlocks(sobiFile)) {
            logger.info("Processing "+block);
            try {
                String data = block.getData().toString();
                Bill bill = getOrCreateBill(block, date, storage);
                switch (block.getType()) {
                    case '1': applyBillInfo(data, bill, date); break;
                    case '2': applyLawSection(data, bill, date); break;
                    case '3': applyTitle(data, bill, date); break;
                    case '4': applyBillEvent(data, bill, date); break;
                    case '5': applySameAs(data, bill, date); break;
                    case '6': applySponsor(data, bill, date); break;
                    case '7': applyCosponsors(data, bill, date); break;
                    case '8': applyMultisponsors(data, bill, date); break;
                    case '9': applyProgramInfo(data, bill, date); break;
                    case 'A': applyActClause(data, bill, date); break;
                    case 'B': applyLaw(data, bill, date); break;
                    case 'C': applySummary(data, bill, date); break;
                    case 'M': // Sponsor memo text - handled by applyText
                    case 'R': // Resolution text - handled by applyText
                    case 'T': applyText(data, bill, date); break;
                    case 'V': applyVoteMemo(data, bill, date); break;
                    default: throw new ParseError("Invalid Line Code "+block.getType() );
                }
                bill.addDataSource(sobiFile.getName());
                saveBill(bill, storage);
            }
            catch (ParseError e) {
                logger.error("ParseError at "+block.getLocation(), e);
            }
            catch (Exception e) {
                logger.error("Unexpected Exception at "+block.getLocation(), e);
            }
        }
    }

    /**
     * Parses the given SOBI file into a list of blocks. Replaces null bytes in
     * each line with spaces to bring them into the proper fixed width formats.
     * <p>
     * See the Block class for more details.
     *
     * @param sobiFile
     * @return
     * @throws IOException if file cannot be opened for reading.
     */
    public List<SOBIBlock> getBlocks(File sobiFile) throws IOException
    {
        SOBIBlock block = null;
        List<SOBIBlock> blocks = new ArrayList<SOBIBlock>();
        List<String> lines = FileUtils.readLines(sobiFile);
        lines.add(""); // Add a trailing line to end the last block and remove edge cases

        for(int lineNum = 0; lineNum < lines.size(); lineNum++) {
            // Replace NULL bytes with spaces to properly format lines.
            String line = lines.get(lineNum).replace('\0', ' ');

            // Source file is not assumed to be 100% SOBI so we filter out other lines
            Matcher headerMatcher = SOBIBlock.blockPattern.matcher(line);

            if (headerMatcher.find()) {
                if (block == null) {
                    // No active block with a new matching line: create new block
                    block = new SOBIBlock(sobiFile, lineNum, line);
                }
                else if (block.getHeader().equals(headerMatcher.group()) && block.isMultiline()) {
                    // active multi-line block with a new matching line: extend block
                    block.extend(line);
                }
                else {
                    // active block does not match new line or can't be extended: create new block
                    blocks.add(block);
                    SOBIBlock newBlock = new SOBIBlock(sobiFile, lineNum, line);

                    // Handle certain SOBI grouping edge cases.
                    if (newBlock.getBillHeader().equals(block.getBillHeader())) {
                        // The law code line can be omitted when blank but it always precedes the 'C' line
                        if (newBlock.getType() == 'C' && block.getType() != 'B') {
                            blocks.add(new SOBIBlock(sobiFile, lineNum, block.getBillHeader()+"B"));
                        }
                    }

                    // Start a new block
                    block = newBlock;
                }
            }
            else if (block != null) {
                // Active block with non-matching line: end the current blockAny non-matching line ends the current block
                blocks.add(block);
                block = null;
            }
            else {
                // No active block with a non-matching line, do nothing
            }
        }

        return blocks;
    }

    /**
     * Safely gets the bill specified by the block from storage. If the bill does not
     * exist then it is created. When loading an amendment, if the parent bill does not
     * exist then it is created as well.
     * <p>
     * New bills will pull sponsor, amendment, law, and summary information up from the
     * prior active version (if available).
     *
     * @param block
     * @param storage
     * @return
     * @throws ParseError
     */
    public Bill getOrCreateBill(SOBIBlock block, Date date, Storage storage)
    {
        String billId = block.getPrintNo()+block.getAmendment()+"-"+block.getYear();
        Bill bill = storage.getBill(block.getPrintNo()+block.getAmendment(), block.getYear());

        if (bill == null) {
            bill = new Bill(billId, block.getYear());
            bill.setModifiedDate(date);
        }

        // If this bill is currently published or it is a new base bill
        // then we don't need to worry about syncing up all the versions.
        if (bill.isPublished() || block.getAmendment().isEmpty()) {
            return bill;
        }

        // All amendments are based on the original bill. New and unpublished
        // bill amendments need to sync certain data with the base bill.
        String baseBillId = block.getPrintNo()+"-"+block.getYear();
        Bill baseBill = storage.getBill(block.getPrintNo(), block.getYear());
        if (baseBill == null) {
            // Amendments should always have original bills already made, make it happen
            logger.warn("Bill Amendment filed without initial bill at "+block.getLocation()+" - "+block.getHeader());
            baseBill = new Bill(baseBillId, block.getYear());
            baseBill.setModifiedDate(date);
            // TODO: This isn't quite right since it isn't published..
            // ??/
            storage.set(baseBill);
        }

        // Base bills are always the first "amendment" to a bill.
        // Grab any other amendments that the base bill knows about.
        bill.setAmendments(new ArrayList<String>(Arrays.asList(baseBillId)));
        bill.addAmendments(baseBill.getAmendments());

        // Pull shared information up from the base bill
        bill.setSponsor(baseBill.getSponsor());
        bill.setCoSponsors(baseBill.getCoSponsors());
        bill.setOtherSponsors(baseBill.getOtherSponsors());
        bill.setMultiSponsors(baseBill.getMultiSponsors());
        bill.setSummary(baseBill.getSummary());
        bill.setLaw(baseBill.getLaw());

        // Some information isn't exactly shared but should be pulled
        // up from the previously active version of the bill until a
        // proper update comes through for the data.
        for (String versionKey : bill.getAmendments()) {
            // Sometimes a re-published bill version was more recently updated than the
            // currently active bill version. In these cases, don't update values.
            Bill billVersion = storage.getBill(versionKey);
            logger.info(versionKey + " - "+billVersion.getModifiedDate()+"; "+bill.getBillId() + " - "+bill.getModifiedDate());
            if (billVersion.isActive() && billVersion.getModifiedDate().getTime() > bill.getModifiedDate().getTime()) {
                bill.setTitle(billVersion.getTitle());
                bill.setActClause(billVersion.getActClause());
                bill.setLawSection(billVersion.getLawSection());
            }
        }

        return bill;
    }


    /**
     * Safely saves the bill to storage. This makes sure to sync sponsor data across all versions
     * so that different versions of the bill cannot get out of sync
     *
     * @param bill
     * @param storage
     */
    public void saveBill(Bill bill, Storage storage)
    {
        logger.info("SAVING "+bill.getBillId());
        // Until LBDC starts sending coPrime information for real we need overrides
        // for the following set of bills and resolutions
        if (bill.getBillId().equals("R314-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("J375-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("SKELOS"), new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("R633-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("J694-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("J758-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("SKELOS")));
        }
        else if (bill.getBillId().equals("R818-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("J844-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("J860-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("SKELOS")));
        }
        else if (bill.getBillId().equals("J1608-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("STEWART-COUSINS")));
        }
        else if (bill.getBillId().equals("J1938-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("STEWART-COUSINS")));
        }
        else if (bill.getBillId().equals("J3100-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("HANNON")));
        }
        else if (bill.getBillId().equals("S2107-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("S3953-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("ESPAILLAT")));
        }
        else if (bill.getBillId().equals("S5441-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("GRISANTI"), new Person("RANZENHOFER"), new Person("GALLIVAN")));
        }
        else if (bill.getBillId().equals("S5656-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("FUSCHILLO")));
        }
        else if (bill.getBillId().equals("S5657-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("MARCHIONE"), new Person("CARLUCCI")));
        }
        else if (bill.getBillId().equals("S5683-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("VALESKY")));
        }
        else if (bill.getBillId().equals("J2885-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("STEWART-COUSINS")));
        }
        else if (bill.getBillId().equals("J3307-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("SKELOS")));
        }
        else if (bill.getBillId().equals("J3743-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("STEWART-COUSINS")));
        }
        else if (bill.getBillId().equals("J3908-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("STEWART-COUSINS")));
        }
        else if (bill.getBillId().equals("R4036-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }
        else if (bill.getBillId().equals("S6966-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("GRIFFO")));
        }
        else if (bill.getBillId().equals("J4904-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN"), new Person("STEWART-COUSINS")));
        }
        else if (bill.getBillId().equals("J5165-2013")) {
            bill.setOtherSponsors(Arrays.asList(new Person("KLEIN")));
        }

        // Check if the bill is on the unpublished list
        if (unpublishListManager.getUnpublishedBills().contains(bill.getBillId())){
            // If so set the publish date to null
            bill.setPublishDate(null);
        }

        if (bill.isPublished()) {
            // Sponsor and summary information needs to be synced at all times.
            // Normally it is always sent to the base bill and broadcasted to amendments
            // In our 2009 data set we are missing tons of base amendments and it actually
            // needs to be broadcasted backwards to the original bill.
            // Make sure we are in the amendment list and that if we are active, that no one else is
            for (String versionKey : bill.getAmendments()) {
                Bill billVersion = storage.getBill(versionKey);
                if (!billVersion.getAmendments().contains(bill.getBillId())) {
                    billVersion.addAmendment(bill.getBillId());
                }
                if (bill.isActive()) {
                    billVersion.setActive(false);
                }
                billVersion.setSponsor(bill.getSponsor());
                billVersion.setCoSponsors(bill.getCoSponsors());
                billVersion.setOtherSponsors(bill.getOtherSponsors());
                billVersion.setMultiSponsors(bill.getMultiSponsors());
                billVersion.setLawSection(bill.getLawSection());
                billVersion.setLaw(bill.getLaw());
                billVersion.setSummary(bill.getSummary());
                storage.set(billVersion);
                ChangeLogger.record(storage.key(billVersion), storage);
            }
            if (bill.isUniBill()) {
                // Uni bills share text, always sent to the senate bill.
                Bill uniBill = storage.getBill(bill.getSameAs());
                if (uniBill != null) {
                    String billText = bill.getFulltext();
                    String uniBillText = uniBill.getFulltext();

                    // If senate bill/reso, copy to assembly
                    if (bill.getBillId().matches("^[SJBR]")) {
                        uniBill.setFulltext(bill.getFulltext());
                        storage.set(uniBill);
                        ChangeLogger.record(storage.key(uniBill), storage);
                    }
                    // Copy from senate bill
                    else if (!billText.equals(uniBillText)) {
                        bill.setFulltext(uniBillText);
                    }
                }
            }

            storage.set(bill);
            ChangeLogger.record(storage.key(bill), storage);
        }
        else {
            // When saving an unpublished bill make sure that references to it are removed
            // from other bills and find a new bill to make active.
            List<String> amendments = bill.getAmendments();
            if (amendments.size() > 0) {
                // This assumes that the last amendment on the list is the most recent one.
                // TODO: In rare cases with multiple substitutions this might not be the right thing to do!
                String newActiveBill = amendments.get(amendments.size()-1);

                // Remove all references to the unpublished bill from other versions.
                for (String versionKey : bill.getAmendments()) {
                    Bill billVersion = storage.getBill(versionKey);
                    billVersion.removeAmendment(bill.getBillId());
                    if (bill.isActive() && versionKey.equals(newActiveBill)) {
                        billVersion.setActive(true);
                    }
                    storage.set(billVersion);
                    ChangeLogger.record(storage.key(billVersion), storage);
                }
            }
            // Deactivate ourselves.
            bill.setActive(false);
            storage.set(bill);
            if (!bill.isBrandNew()) {
                ChangeLogger.delete(storage.key(bill), storage);
            }
        }
    }

    /**
     * Applies the data to the bill title. Strips out all whitespace formatting and replaces
     * existing content in full.
     * <p>
     * The bill title is a required field and cannot be deleted, only replaced.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyTitle(String data, Bill bill, Date date) throws ParseError
    {
        bill.setTitle(data.replace("\n", " ").trim());
    }

    /**
     * Applies data to the bill Same-as replacing existing content fully.
     * <p>
     * Both "No same as" and "DELETE" data blocks are treated as empty values for same as content.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applySameAs(String data, Bill bill, Date date) throws ParseError
    {
        if (data.trim().equalsIgnoreCase("No same as") || data.trim().equalsIgnoreCase("DELETE")) {
            bill.setSameAs("");
            bill.setUniBill(false);
        }
        else {
            // Why do we do this, don't have an example of this issue, here is some legacy code:
            //     line = line.replace("/", ",").replaceAll("[ \\-.;]", "");
            Matcher sameAsMatcher = sameAsPattern.matcher(data);
            if (sameAsMatcher.find()) {
                if (sameAsMatcher.group(1) != null && !sameAsMatcher.group(1).isEmpty()) {
                    bill.setUniBill(true);
                }
                bill.setSameAs(sameAsMatcher.group(2).replace("-","").replace(" ","")+"-"+bill.getSession());
            } else {
                logger.error("sameAsPattern not matched: "+data);
            }
        }
    }

    /**
     * Applies data to bill sponsor. Fully replaces existing sponsor information. Because
     * this is a one line field the block parser is sometimes tricked into combining consecutive
     * blocks. Make sure to process the data 1 line at a time.
     * <p>
     * A delete in these field removes all sponsor information.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applySponsor(String data, Bill bill, Date date) throws ParseError
    {
        // Apply the lines in order given as each represents its own "block"
        for(String line : data.split("\n")) {
            if (line.trim().equals("DELETE")) {
                bill.setSponsor(null);
                bill.setCoSponsors(new ArrayList<Person>());
                bill.setMultiSponsors(new ArrayList<Person>());

            } else {
                // An old bug with the assembly sponsors field needs to be corrected, NYSS 7215
                if (bill.getSponsor() != null && bill.getSponsor().getFullname().startsWith("RULES ")) {
                    final String sponsorMatch = "RULES COM ([a-zA-Z-']+)( [A-Z])?(.*)";
                    final String sponsorReplacement = "RULES (REQUEST OF $1$2)";
                    final String sponsorReplacementMatch = "RULES \\(REQUEST OF [a-zA-Z-']*\\)";

                    String sponsorName = bill.getSponsor().getFullname();
                    if (sponsorName.matches(sponsorMatch)) {
                        bill.getSponsor().setFullname(sponsorName.replaceAll(sponsorMatch, sponsorReplacement).toUpperCase());
                    }
                    else if (!sponsorName.matches(sponsorReplacementMatch)) {
                        bill.getSponsor().setFullname("RULES");
                    }
                }
                else {
                    bill.setSponsor(new Person(line.trim()));
                }
            }
        }
    }

    /**
     * Applies data to bill co-sponsors. Expects a comma separated list and
     * fully replaces existing co-sponsor information.
     * <p>
     * Delete code is sent through the sponsor block.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyCosponsors(String data, Bill bill, Date date) throws ParseError
    {
        ArrayList<Person> coSponsors = new ArrayList<Person>();
        for(String coSponsor : data.replace("\n", " ").split(",")) {
            coSponsors.add(new Person(coSponsor.trim()));
        }
        bill.setCoSponsors(coSponsors);
    }

    /**
     * Applies data to bill multi-sponsors. Expects a comma separated list and
     * fully replaces existing information.
     * <p>
     * Delete code is sent through the sponsor block.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyMultisponsors(String data, Bill bill, Date date) throws ParseError
    {
        ArrayList<Person> multiSponsors = new ArrayList<Person>();
        for(String multiSponsor : data.replace("\n", " ").split(",")) {
            multiSponsors.add(new Person(multiSponsor.trim()));
        }
        bill.setMultiSponsors(multiSponsors);
    }


    /**
     * Applies data to bill law. Fully replaces existing information.
     * <p>
     * DELETE code here also deletes the bill summary.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyLaw(String data, Bill bill, Date date) throws ParseError
    {
        // This is theoretically not safe because a law line *could* start with DELETE
        // We can't do an exact match because B can be multi-line
        if (data.trim().startsWith("DELETE")) {
            bill.setLaw("");
            bill.setSummary("");
        }
        else {
            bill.setLaw(data.replace("\n", " ").trim());
        }
    }

    /**
     * Applies the data to the bill summary. Strips out all whitespace formatting and replaces
     * existing content in full.
     * <p>
     * Delete codes for this field are sent through the law block.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applySummary(String data, Bill bill, Date date) throws ParseError
    {
        bill.setSummary(data.replace("\n", " ").trim());
    }

    /**
     * Applies data to law section. Fully replaces existing data.
     * <p>
     * Cannot be deleted, only replaced.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyLawSection(String data, Bill bill, Date date) throws ParseError
    {
        bill.setLawSection(data.trim());
    }

    /**
     * Applies data to the ACT TO clause. Fully replaces existing data.
     * <p>
     * DELETE code removes existing ACT TO clause.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyActClause(String data, Bill bill, Date date) throws ParseError
    {
        if (data.trim().equals("DELETE")) {
            bill.setActClause("");
        }
        else {
            bill.setActClause(data.replace("\n", " ").trim());
        }
    }

    /**
     * Applies data to bill Program. Fully replaces existing information
     * <pre>
     *      029 Governor Program
     * </pre>
     * Currently not implemented in the bill data model.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyProgramInfo(String data, Bill bill, Date date) throws ParseError
    {
        // This information currently isn't used for anything
        //if (!data.equals(""))
        //    throw new ParseError("Program info not implemented", data);
    }


    /**
     * Apply information from the Bill Info block. Fully replaces existing information.
     * <p>
     * Currently fills in blank sponsors (doesn't replace existing sponsor information)
     * and previous version information (which has known issues).
     * <p>
     * A DELETE code sent with this block causes the bill to be unpublished.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyBillInfo(String data, Bill bill, Date date) throws ParseError
    {
        if (data.startsWith("DELETE")) {
            bill.setPublishDate(null);
            return;
        }

        else if (bill.isPublished() == false) {
            // When we get a status line for an unpublished bill, (re)publish it and activate it
            // TODO: Marking as active isn't always the right thing to do. A base bill could be
            //       unpublished/republished behind a currently active amendment for now particular reason.
            bill.setPublishDate(date);
            bill.setActive(true);
        }

        Matcher billData = billInfoPattern.matcher(data);
        if (billData.find()) {
            // sponsor, reprint billno/amd, blurb, old bill house/billno/amd, LBD Number, ??, year, ??
            String sponsor = billData.group(1).trim();
            String oldbill = billData.group(4).trim().replaceAll("[0-9`-]$", "");
            String oldYear = billData.group(6).trim();

            if (!sponsor.isEmpty() && (bill.getSponsor() == null || bill.getSponsor().getFullname().isEmpty())) {
                bill.setSponsor(new Person(sponsor));
            }
            if (!oldbill.trim().startsWith("0")) {
                bill.setPreviousVersions(Arrays.asList(oldbill + "-" + oldYear));
            }
        } else {
            throw new ParseError("billDataPattern not matched by "+data);
        }
    }

    /**
     * Applies information to create or replace a bill vote. Votes are
     * uniquely identified by date/bill. If we have an existing vote on
     * the same date, replace it; otherwise create a new one.
     * <p>
     * Expected vote format:
     * <pre>
     *  2011S01892 VSenate Vote    Bill: S1892              Date: 01/19/2011  Aye - 41  Nay - 19
     *  2011S01892 VNay  Adams            Aye  Addabbo          Aye  Alesi            Aye  Avella
     * </pre>
     * Valid vote codes:
     * <ul>
     * <li>Nay - Vote against</li>
     * <li>Aye - Vote for</li>
     * <li>Abs - Absent during voting</li>
     * <li>Exc - Excused from voting</li>
     * <li>Abd - Abstained from voting</li>
     * </ul>
     * Deleting votes is not possible
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyVoteMemo(String data, Bill bill, Date date) throws ParseError
    {
        // TODO: Parse out sequence number once LBDC (maybe) includes it, #6531
        // Because sometimes votes are back to back we need to check for headers
        // Example of a double vote entry: SOBI.D110119.T140802.TXT:390
        Vote vote = null;
        for(String line : data.split("\n")) {
            Matcher voteHeader = voteHeaderPattern.matcher(line);
            if (voteHeader.find()) {
                // TODO: this assumes they are the same vote sent twice, what else could happen?
                //Start over if we hit a header, sometimes we get back to back entries.
                try {
                    // Use the old vote if we can find it, otherwise make a new one using now as the publish date
                    vote = new Vote(bill, voteDateFormat.parse(voteHeader.group(2)), Vote.VOTE_TYPE_FLOOR, "1");
                    vote.setPublishDate(date);
                    for (Vote oldVote : bill.getVotes()) {
                        if (oldVote.equals(vote)) {
                            // If we've received this vote before, use the old publish date
                            vote.setPublishDate(oldVote.getPublishDate());
                            break;
                        }
                    }
                    vote.setModifiedDate(date);
                } catch (ParseException e) {
                    throw new ParseError("voteDateFormat not matched: "+line);
                }

            }
            else if (vote!=null){
                //Otherwise, build the existing vote
                Matcher voteLine = votePattern.matcher(line);
                while(voteLine.find()) {
                    String type = voteLine.group(1).trim();
                    Person voter = new Person(voteLine.group(2).trim());

                    if (type.equals("Aye")) {
                        vote.addAye(voter);
                    }
                    else if (type.equals("Nay")) {
                        vote.addNay(voter);
                    }
                    else if (type.equals("Abs")) {
                        vote.addAbsent(voter);
                    }
                    else if (type.equals("Abd")) {
                        vote.addAbstain(voter);
                    }
                    else if (type.equals("Exc")) {
                        vote.addExcused(voter);
                    }
                    else {
                        throw new ParseError("Unknown vote type found: "+line);
                    }
                }

            } else {
                throw new ParseError("Hit vote data without a header: "+data);
            }
        }

        // Misnomer, will actually update the vote if it already exists
        bill.updateVote(vote);
    }

    /**
     * Applies information to bill text or memo; replaces any existing information.
     *
     * Expected format:
     * <pre>
     * SOBI.D101201.T152619.TXT:2011S00063 T00000.SO DOC S 63                                     BTXT                 2011
     * SOBI.D101201.T152619.TXT:2011S00063 T00083   28    S 4. This act shall take effect immediately.
     * SOBI.D101201.T152619.TXT:2011S00063 T00000.SO DOC S 63            *END*                    BTXT                 2011
     * </pre>
     * Header lines start with 00000.SO DOC and contain one of three actions:
     * <ul>
     * <li>'' - Start of the bill text</li>
     * <li>*END* - End of the bill text</li>
     * <li>*DELETE* - Deletes existing bill text</li>
     * </ul>
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyText(String data, Bill bill, Date date) throws ParseError
    {
        // BillText, ResolutionText, and MemoText can be handled the same way
        // Because Text Blocks can be back to back we constantly look for headers
        // with actions that tell us to start over, end, or delete.
        String type = "";
        StringBuffer text = null;

        for (String line : data.split("\n")) {
            Matcher header = textHeaderPattern.matcher(line);
            if (line.startsWith("00000") && header.find()) {
                //TODO: If house == C then bills can be used for SAME AS verification
                // e.g. 2013S02278 T00000.SO DOC C 2279/2392
                // and  2013S02277 5Same as Uni. A 2394
                //String house = header.group(1);
                //String bills = header.group(2).trim();
                //String year = header.group(5);
                String action = header.group(3).trim();
                type = header.group(4).trim();

                if (!type.equals("BTXT") && !type.equals("RESO TEXT") && !type.equals("MTXT"))
                    throw new ParseError("Unknown text type found: "+type);

                if (action.equals("*DELETE*")) {
                    if (type.equals("BTXT") || type.equals("RESO TEXT")) {
                        bill.setFulltext("");
                    } else if (type.equals("MTXT")) {
                        bill.setMemo("");
                    }

                } else if (action.equals("*END*")) {
                    if (text != null) {
                        if (type.equals("BTXT") || type.equals("RESO TEXT")) {
                            bill.setFulltext(text.toString());
                        } else if (type.equals("MTXT")) {
                            bill.setMemo(text.toString());
                        }
                        text = null;
                    } else {
                        throw new ParseError("Text END Found before a body: "+line);
                    }
                } else if (action.equals("")) {
                    if (text == null) {
                        //First header for this text segment so initialize
                        text = new StringBuffer();
                        text.ensureCapacity(data.length());
                    } else {
                        //Every 100th line is a repeated header for some reason
                    }
                } else {
                    throw new ParseError("Unknown text action found: "+action);
                }
            } else if (text != null) {
                // Remove the leading numbers
                text.append(line.substring(5)+"\n");

            } else {
                throw new ParseError("Text Body found before header: "+line);
            }
        }

        if (text != null) {
            // This is a known issue that was resolved on 03/23/2011
            if (new GregorianCalendar(2011, 3, 23).after(date)) {
                throw new ParseError("Finished text data without a footer");


            } else {
                // Commit what we have and move on
                if (type.equals("BTXT") || type.equals("RESO TEXT")) {
                    bill.setFulltext(text.toString());
                } else if (type.equals("MTXT")) {
                    bill.setMemo(text.toString());
                }
            }
        }
    }


    /**
     * Applies information to bill events; replaces existing information in full.
     * Events are uniquely identified by text/date/bill. In cases where the same
     * action is made on the same day to the same bill (which can happen in June)
     * the date is incremented by 1 second until it becomes unique.
     * <p>
     * Also parses bill events to apply several other bits of meta data to bills:
     * <ul>
     * <li>sameAs - "Substituted ..."</li>
     * <li>stricken - "ENACTING CLAUSE STRICKEN"</li>
     * <li>currentCommittee - "Committed to ..."</li>
     * <li>pastCommittees</li>
     * <ul>
     * <p>
     * There are currently no checks for the action list starting over again which
     * could lead back to back action blocks for a bill to produce a double long list.
     * <p>
     * Bill events cannot be deleted, only replaced.
     *
     * @param data
     * @param bill
     * @param date
     * @throws ParseError
     */
    public void applyBillEvent(String data, Bill bill, Date date) throws ParseError
    {
        ArrayList<Action> actions = new ArrayList<Action>();
        String sameAs = bill.getSameAs();
        Boolean stricken = false;
        String currentCommittee = "";
        List<String> pastCommittees = new ArrayList<String>();

        for (String line : data.split("\n")) {
            Matcher billEvent = billEventPattern.matcher(line);
            if (billEvent.find()) {
                try {
                    Date eventDate = eventDateFormat.parse(billEvent.group(1));
                    String eventText = billEvent.group(2).trim();

                    // Horrible hack - fixes instances where multiple identical events
                    // occur on the same day on the same bill. Increment the time by
                    // seconds until we get clear. This also allows for strict ordering
                    // by date. Assume events come in chronological order.
                    // TODO: fix this horrible hack somehow
                    // TODO: include some example sobi files for reference
                    // TODO: account for multiple action blocks for the same bill in a row
                    Calendar c = Calendar.getInstance();
                    c.setTime(eventDate);
                    for(Action event : actions) {
                        if(event.getDate().equals(c.getTime())) {
                            c.set(Calendar.SECOND, c.get(Calendar.SECOND) + 1);
                        }
                    }
                    Action action = new Action(c.getTime(), eventText, bill);
                    action.setBill(null); // This is terribad.
                    actions.add(action);

                    eventText = eventText.toUpperCase();

                    Matcher committeeEventText = committeeEventTextPattern.matcher(eventText);
                    Matcher substituteEventText = substituteEventTextPattern.matcher(eventText);
                    Matcher floorEventText = floorEventTextPattern.matcher(eventText);

                    if (eventText.contains("ENACTING CLAUSE STRICKEN")) {
                        stricken = true;
                    } else if (committeeEventText.find()) {
                        if (!currentCommittee.isEmpty())
                            pastCommittees.add(currentCommittee);
                        currentCommittee = committeeEventText.group(2);
                    } else if (floorEventText.find()){
                        if(!currentCommittee.isEmpty()) {
                            pastCommittees.add(currentCommittee);
                        }
                        currentCommittee = "";
                    } else if(substituteEventText.find()) {
                        sameAs = substituteEventText.group(2)+"-"+bill.getSession();
//                        String newSameAs = substituteEventText.group(2);
//                        for(String billId : sameAs.split(",")) {
//                            if (!billId.trim().isEmpty()) {
//                                newSameAs += ", " + billId.trim()+"-"+bill.getYear();
//                            }
//                        }
//                        sameAs = newSameAs;
                    }

                } catch (ParseException e) {
                    throw new ParseError("eventDateFormat parse failure: "+billEvent.group(1));
                }
            } else {
                throw new ParseError("billEventPattern not matched: "+line);
            }
        }

        bill.setActions(actions);
        bill.setSameAs(sameAs);
        bill.setCurrentCommittee(currentCommittee);
        bill.setPastCommittees(pastCommittees);
        bill.setStricken(stricken);
    }
}