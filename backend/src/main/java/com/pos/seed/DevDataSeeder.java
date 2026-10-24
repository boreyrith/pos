package com.pos.seed;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.access.Role;
import com.pos.access.RoleRepository;
import com.pos.auth.User;
import com.pos.auth.UserRepository;
import com.pos.catalog.Category;
import com.pos.catalog.CategoryRepository;
import com.pos.catalog.Offering;
import com.pos.catalog.OfferingRepository;
import com.pos.catalog.OfferingPack;
import com.pos.catalog.PackageLine;
import com.pos.catalog.OfferingType;
import com.pos.catalog.PricingMode;
import com.pos.catalog.ServiceComponent;
import com.pos.catalog.UnitRepository;
import com.pos.inventory.Branch;
import com.pos.inventory.BranchRepository;
import com.pos.inventory.MovementType;
import com.pos.inventory.StockService;
import com.pos.inventory.Storage;
import com.pos.inventory.StorageRepository;
import com.pos.tax.TaxRateRepository;

/**
 * Fills a development database with demo users and a kids fashion and baby store: clothing by size,
 * shoes, baby care, diapers, feeding, toys and gear, store supplies, services and gift sets, with
 * opening stock in the main branch's showroom. Each part is only
 * added when missing, so an older dev database gets the newer demo data too.
 * Demo logins: admin / admin1234, manager / manager1234, cashier / cashier1234 (all at Main
 * branch), srmanager / srmanager1234 (manager at Siem Reap), and on a fresh database
 * tech / tech12345 (a cashier who can be assigned to services).
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository users;
    private final CategoryRepository categories;
    private final OfferingRepository offerings;
    private final UnitRepository units;
    private final PasswordEncoder passwordEncoder;
    private final BranchRepository branches;
    private final StorageRepository storages;
    private final StockService stock;
    private final TaxRateRepository taxRates;
    private final RoleRepository roles;
    private final com.pos.purchasing.VendorRepository vendors;

    public DevDataSeeder(UserRepository users, CategoryRepository categories, OfferingRepository offerings,
                         UnitRepository units, PasswordEncoder passwordEncoder, BranchRepository branches,
                         StorageRepository storages, StockService stock, TaxRateRepository taxRates, RoleRepository roles,
                         com.pos.purchasing.VendorRepository vendors) {
        this.vendors = vendors;
        this.roles = roles;
        this.taxRates = taxRates;
        this.branches = branches;
        this.storages = storages;
        this.stock = stock;
        this.users = users;
        this.categories = categories;
        this.offerings = offerings;
        this.units = units;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Branch main = branches.findAll().stream().filter(b -> b.getCode().equals("MAIN")).findFirst().orElseThrow();
        Branch siemReap = seedBranches(main);
        if (users.count() == 0) {
            users.save(new User("admin", passwordEncoder.encode("admin1234"), "Store Admin", role(Role.SUPER_ADMIN), main));
            users.save(new User("manager", passwordEncoder.encode("manager1234"), "Sokha Manager", role("MANAGER"), main));
            users.save(new User("cashier", passwordEncoder.encode("cashier1234"), "Dara Cashier", role("CASHIER"), main));
            users.save(new User("tech", passwordEncoder.encode("tech12345"), "Vibol Technician", role("CASHIER"), main));
            log.info("Seeded demo users");
        }
        if (users.findByUsername("srmanager").isEmpty()) {
            users.save(new User("srmanager", passwordEncoder.encode("srmanager1234"), "Chenda Manager", role("MANAGER"),
                    siemReap));
        }
        if (users.findByUsername("stock").isEmpty()) {
            users.save(new User("stock", passwordEncoder.encode("stock12345"), "Mony Stock Keeper", role("INVENTORY_STAFF"), main));
        }
        if (users.findByUsername("accountant").isEmpty()) {
            users.save(new User("accountant", passwordEncoder.encode("accountant1234"), "Bopha Accountant", role("ACCOUNTANT"), main));
        }
        if (vendors.count() == 0) {
            vendors.save(vendor("V-LTG", "Little Threads Garment Co.", "Srey Leak", "+855 12 801 101", 30));
            vendors.save(vendor("V-BCD", "BabyCare Distribution (Cambodia)", "Visal", "+855 12 801 102", 15));
            vendors.save(vendor("V-TTF", "Tiny Toes Footwear", "Pisey", "+855 12 801 103", 30));
            vendors.save(vendor("V-PWT", "PlayWorld Toys Import", "Kosal", "+855 12 801 104", 0));
            vendors.save(vendor("V-APS", "Angkor Packaging Supply", "Ratha", "+855 12 801 105", 7));
            log.info("Seeded demo vendors");
        }
        Map<String, Category> cats = new HashMap<>();
        categories.findAll().forEach(c -> cats.put(c.getName(), c));

        if (offerings.countByType(OfferingType.PRODUCT) == 0) {
            seedProducts(cats);
        }
        if (offerings.countByType(OfferingType.SERVICE) == 0) {
            seedItemsServicesAndPackages(cats);
        }
    }

    /** A back store at the main branch and a second branch with a warehouse, for trying transfers. */
    private Branch seedBranches(Branch main) {
        // Names are compared the way the database does (case-insensitively), so a renamed "Back Store" counts.
        if (storages.findByBranchId(main.getId()).stream().noneMatch(st -> st.getName().trim().equalsIgnoreCase("Back store"))) {
            storages.save(new Storage(main, "Back store", false));
        }
        return branches.findAll().stream().filter(b -> b.getCode().equals("SRP")).findFirst().orElseGet(() -> {
            Branch b = branches.save(new Branch("SRP", "Siem Reap", "Sivatha Blvd, Siem Reap"));
            storages.save(new Storage(b, "Shop floor", true));
            storages.save(new Storage(b, "Warehouse", false));
            log.info("Seeded demo branch Siem Reap");
            return b;
        });
    }

    /**
     * The demo catalog: a kids fashion and baby store. Each size of a garment is its own entry (its
     * own SKU and barcode), the way a shelf label and a scanner see it.
     * Columns: SKU, name, Khmer name, category, unit, price, cost, opening stock, low-stock level.
     */
    private static final Object[][] PRODUCTS = {
            // Baby clothing
            {"BC-BOD-03", "Organic Cotton Bodysuit 3-Pack (0-3M)", "អាវខោជាប់ទារក ៣ឈុត (០-៣ខែ)", "Baby Clothing", "pack", "12.90", "6.20", 36, 8},
            {"BC-BOD-36", "Organic Cotton Bodysuit 3-Pack (3-6M)", "អាវខោជាប់ទារក ៣ឈុត (៣-៦ខែ)", "Baby Clothing", "pack", "12.90", "6.20", 32, 8},
            {"BC-BOD-12", "Organic Cotton Bodysuit 3-Pack (6-12M)", "អាវខោជាប់ទារក ៣ឈុត (៦-១២ខែ)", "Baby Clothing", "pack", "13.90", "6.70", 24, 6},
            {"BC-SLP-03", "Footed Sleepsuit Stars (0-3M)", "ឈុតគេងទារកមានស្រោមជើង (០-៣ខែ)", "Baby Clothing", "pcs", "9.50", "4.40", 20, 5},
            {"BC-SLP-36", "Footed Sleepsuit Stars (3-6M)", "ឈុតគេងទារកមានស្រោមជើង (៣-៦ខែ)", "Baby Clothing", "pcs", "9.50", "4.40", 18, 5},
            {"BC-HAT-NB", "Newborn Knit Hat", "មួកក្រណាត់ទារកទើបកើត", "Baby Clothing", "pcs", "3.50", "1.20", 40, 10},
            {"BC-MIT-NB", "Scratch Mittens 2 Pairs", "ស្រោមដៃទារក ២គូ", "Baby Clothing", "pack", "2.90", "0.90", 45, 10},
            {"BC-SWD-01", "Muslin Swaddle Blanket 2-Pack", "ភួយរុំទារក ២ផ្ទាំង", "Baby Clothing", "pack", "14.90", "6.80", 22, 5},
            {"BC-BIB-03", "Waterproof Bibs 3-Pack", "បាវៀទារកការពារទឹក ៣", "Baby Clothing", "pack", "5.90", "2.30", 30, 8},
            // Girls
            {"GC-DRS-2T", "Floral Cotton Dress (2T)", "រ៉ូបកប្បាសផ្កា (2T)", "Girls Clothing", "pcs", "15.90", "6.90", 10, 3},
            {"GC-DRS-3T", "Floral Cotton Dress (3T)", "រ៉ូបកប្បាសផ្កា (3T)", "Girls Clothing", "pcs", "15.90", "6.90", 12, 3},
            {"GC-DRS-4T", "Floral Cotton Dress (4T)", "រ៉ូបកប្បាសផ្កា (4T)", "Girls Clothing", "pcs", "15.90", "6.90", 12, 3},
            {"GC-DRS-5Y", "Floral Cotton Dress (5Y)", "រ៉ូបកប្បាសផ្កា (5Y)", "Girls Clothing", "pcs", "16.90", "7.30", 8, 3},
            {"GC-LEG-2T", "Stretch Leggings Pink (2T)", "ខោជើងវែងយឺតពណ៌ផ្កាឈូក (2T)", "Girls Clothing", "pcs", "6.50", "2.60", 18, 5},
            {"GC-LEG-4T", "Stretch Leggings Pink (4T)", "ខោជើងវែងយឺតពណ៌ផ្កាឈូក (4T)", "Girls Clothing", "pcs", "6.50", "2.60", 18, 5},
            {"GC-TEE-4Y", "Unicorn T-Shirt (4Y)", "អាវយឺតយូនីខន (4Y)", "Girls Clothing", "pcs", "7.90", "3.10", 15, 4},
            {"GC-TEE-6Y", "Unicorn T-Shirt (6Y)", "អាវយឺតយូនីខន (6Y)", "Girls Clothing", "pcs", "7.90", "3.10", 15, 4},
            {"GC-SKT-46", "Tulle Party Skirt (4-6Y)", "សំពត់ទុលពិធីជប់លៀង (4-6Y)", "Girls Clothing", "pcs", "12.50", "5.20", 6, 3},
            // Boys
            {"BY-TEE-2T", "Dinosaur T-Shirt (2T)", "អាវយឺតរូបដាយណូស័រ (2T)", "Boys Clothing", "pcs", "7.90", "3.10", 16, 4},
            {"BY-TEE-4T", "Dinosaur T-Shirt (4T)", "អាវយឺតរូបដាយណូស័រ (4T)", "Boys Clothing", "pcs", "7.90", "3.10", 16, 4},
            {"BY-TEE-6Y", "Dinosaur T-Shirt (6Y)", "អាវយឺតរូបដាយណូស័រ (6Y)", "Boys Clothing", "pcs", "7.90", "3.10", 14, 4},
            {"BY-SHT-4Y", "Denim Shorts (4Y)", "ខោខ្លីខូវប៊ូយ (4Y)", "Boys Clothing", "pcs", "11.90", "5.10", 12, 3},
            {"BY-SHT-6Y", "Denim Shorts (6Y)", "ខោខ្លីខូវប៊ូយ (6Y)", "Boys Clothing", "pcs", "11.90", "5.10", 12, 3},
            {"BY-POL-4Y", "Polo Shirt Navy (4Y)", "អាវប៉ូឡូពណ៌ខៀវចាស់ (4Y)", "Boys Clothing", "pcs", "10.90", "4.60", 10, 3},
            {"BY-POL-6Y", "Polo Shirt Navy (6Y)", "អាវប៉ូឡូពណ៌ខៀវចាស់ (6Y)", "Boys Clothing", "pcs", "10.90", "4.60", 10, 3},
            {"BY-JOG-4Y", "Jogger Pants Grey (4Y)", "ខោកីឡាជើងវែងពណ៌ប្រផេះ (4Y)", "Boys Clothing", "pcs", "9.90", "4.20", 12, 3},
            // Sleepwear
            {"SW-PJS-2T", "Cotton Pajama Set (2T)", "ឈុតគេងកប្បាស (2T)", "Sleepwear", "set", "13.90", "6.00", 10, 3},
            {"SW-PJS-4T", "Cotton Pajama Set (4T)", "ឈុតគេងកប្បាស (4T)", "Sleepwear", "set", "13.90", "6.00", 10, 3},
            {"SW-PJS-6Y", "Cotton Pajama Set (6Y)", "ឈុតគេងកប្បាស (6Y)", "Sleepwear", "set", "14.90", "6.40", 8, 3},
            // Shoes
            {"SH-PRW-S", "Soft-Sole Baby Shoes (0-6M)", "ស្បែកជើងទារកបាតទន់ (០-៦ខែ)", "Shoes", "pair", "8.90", "3.40", 14, 4},
            {"SH-PRW-M", "Soft-Sole Baby Shoes (6-12M)", "ស្បែកជើងទារកបាតទន់ (៦-១២ខែ)", "Shoes", "pair", "8.90", "3.40", 14, 4},
            {"SH-SND-24", "Kids Velcro Sandals (EU 24)", "ស្បែកជើងសង្រែកកុមារ (24)", "Shoes", "pair", "14.90", "6.50", 6, 2},
            {"SH-SND-26", "Kids Velcro Sandals (EU 26)", "ស្បែកជើងសង្រែកកុមារ (26)", "Shoes", "pair", "14.90", "6.50", 6, 2},
            {"SH-SNK-28", "Light-Up Sneakers (EU 28)", "ស្បែកជើងប៉ាតាមានភ្លើង (28)", "Shoes", "pair", "24.90", "11.50", 5, 2},
            {"SH-SNK-30", "Light-Up Sneakers (EU 30)", "ស្បែកជើងប៉ាតាមានភ្លើង (30)", "Shoes", "pair", "24.90", "11.50", 2, 2},
            // Accessories
            {"AC-SOC-05", "Kids Ankle Socks 5 Pairs", "ស្រោមជើងកុមារ ៥គូ", "Accessories", "pack", "6.90", "2.40", 40, 10},
            {"AC-HAT-UV", "Sun Hat UPF 50", "មួកការពារកម្តៅថ្ងៃ UPF 50", "Accessories", "pcs", "8.50", "3.30", 15, 4},
            {"AC-HCL-10", "Hair Clips Set of 10", "ឈុតខ្ទាស់សក់ ១០", "Accessories", "set", "3.90", "1.10", 30, 8},
            {"AC-BAG-BR", "Bear Kids Backpack", "កាបូបស្ពាយកុមាររូបខ្លាឃ្មុំ", "Accessories", "pcs", "16.90", "7.50", 8, 2},
            // Baby care
            {"CA-WIP-80", "Baby Wipes 80 Sheets", "ក្រដាសសើមទារក ៨០សន្លឹក", "Baby Care", "pack", "2.50", "1.30", 96, 24},
            {"CA-LOT-200", "Gentle Baby Lotion 200ml", "ឡេលាបទារក ២០០មល", "Baby Care", "bottle", "6.90", "3.40", 30, 8},
            {"CA-SHP-300", "Tear-Free Baby Shampoo 300ml", "សាប៊ូកក់សក់ទារកមិនឈឺភ្នែក ៣០០មល", "Baby Care", "bottle", "7.50", "3.70", 30, 8},
            {"CA-PWD-200", "Baby Powder 200g", "ម្សៅលាបទារក ២០០ក្រាម", "Baby Care", "bottle", "4.50", "2.10", 24, 6},
            {"CA-RSH-50", "Diaper Rash Cream 50g", "ក្រែមការពាររលាកកន្ទប ៥០ក្រាម", "Baby Care", "pcs", "5.90", "2.80", 20, 6},
            // Diapering
            {"DP-TAP-S", "Tape Diapers Size S (4-8kg) 62pcs", "កន្ទបទារក ទំហំ S ៦២សន្លឹក", "Diapering", "pack", "13.50", "9.20", 40, 10},
            {"DP-TAP-M", "Tape Diapers Size M (6-11kg) 56pcs", "កន្ទបទារក ទំហំ M ៥៦សន្លឹក", "Diapering", "pack", "13.50", "9.20", 48, 10},
            {"DP-TAP-L", "Tape Diapers Size L (9-14kg) 48pcs", "កន្ទបទារក ទំហំ L ៤៨សន្លឹក", "Diapering", "pack", "13.50", "9.20", 40, 10},
            {"DP-PNT-XL", "Pants Diapers Size XL (12-17kg) 42pcs", "កន្ទបខោ ទំហំ XL ៤២សន្លឹក", "Diapering", "pack", "14.50", "9.90", 32, 8},
            // Feeding
            {"FD-BTL-150", "Anti-Colic Feeding Bottle 150ml", "ដបទឹកដោះទារក ១៥០មល", "Feeding", "pcs", "8.90", "3.90", 24, 6},
            {"FD-BTL-260", "Anti-Colic Feeding Bottle 260ml", "ដបទឹកដោះទារក ២៦០មល", "Feeding", "pcs", "9.90", "4.30", 24, 6},
            {"FD-NIP-02", "Silicone Teats 2-Pack", "ក្បាលដបស៊ីលីកូន ២", "Feeding", "pack", "4.50", "1.70", 30, 8},
            {"FD-PAC-01", "Orthodontic Pacifier", "បៅស្ងាត់ទារក", "Feeding", "pcs", "4.90", "1.80", 30, 8},
            {"FD-CUP-240", "Sippy Cup 240ml", "កែវហាត់ផឹកទឹក ២៤០មល", "Feeding", "pcs", "6.90", "2.80", 20, 5},
            {"FD-FRM-800", "Infant Formula Stage 1 800g", "ទឹកដោះគោម្សៅទារក ដំណាក់កាល១ ៨០០ក្រាម", "Feeding", "can", "32.00", "26.50", 18, 6},
            {"FD-CER-200", "Baby Rice Cereal 200g", "បបរអង្ករទារក ២០០ក្រាម", "Feeding", "pcs", "4.20", "2.60", 24, 6},
            // Toys
            {"TY-RTL-WD", "Wooden Rattle", "ប្រដាប់រង្គើឈើ", "Toys", "pcs", "5.50", "2.00", 20, 5},
            {"TY-PLH-EL", "Plush Elephant 30cm", "តុក្កតាដំរី ៣០សម", "Toys", "pcs", "12.90", "5.10", 15, 4},
            {"TY-STK-RG", "Stacking Rings", "កងដាក់ត្រួតគ្នា", "Toys", "pcs", "7.90", "3.20", 12, 3},
            {"TY-BLK-12", "Soft Building Blocks 12pcs", "ប្លុកទន់ ១២ដុំ", "Toys", "set", "15.90", "6.80", 10, 3},
            {"TY-DCK-03", "Bath Ducks 3-Pack", "ទាលេងពេលងូតទឹក ៣", "Toys", "pack", "4.90", "1.60", 30, 8},
            // Gear
            {"GR-STR-LT", "Lightweight Travel Stroller", "រទេះរុញទារកស្រាល", "Baby Gear", "pcs", "129.00", "78.00", 4, 2},
            {"GR-CAR-ER", "Ergonomic Baby Carrier", "កាបូបពរទារក", "Baby Gear", "pcs", "39.00", "18.50", 6, 2},
            {"GR-DBG-BP", "Diaper Bag Backpack", "កាបូបស្ពាយសម្ភារៈទារក", "Baby Gear", "pcs", "29.90", "13.50", 7, 2},
    };

    /** Bought by the carton: SKU, pack unit, base units per pack. */
    private static final Object[][] PACKS = {
            {"CA-WIP-80", "carton", 24},
            {"DP-TAP-S", "carton", 4},
            {"DP-TAP-M", "carton", 4},
            {"DP-TAP-L", "carton", 4},
            {"DP-PNT-XL", "carton", 4},
            {"FD-FRM-800", "carton", 6},
            {"AC-SOC-05", "box", 10},
    };

    private void seedProducts(Map<String, Category> cats) {
        Map<String, Offering> bySku = new HashMap<>();
        for (int i = 0; i < PRODUCTS.length; i++) {
            Object[] r = PRODUCTS[i];
            Offering p = stocked(OfferingType.PRODUCT, (String) r[0], (String) r[1], category(cats, (String) r[3]), (String) r[5],
                    (String) r[6], (String) r[4]);
            p.setNameKm((String) r[2]);
            p.setBarcode(ean13("8840101" + String.format("%05d", i + 1)));
            p.setLowStockThreshold((Integer) r[8]);
            bySku.put(p.getSku(), p);
        }
        for (Object[] pack : PACKS) {
            Offering p = bySku.get((String) pack[0]);
            p.getPacks().add(new OfferingPack(p, units.findBySymbol((String) pack[1]).orElseThrow(), (Integer) pack[2]));
        }
        for (Object[] r : PRODUCTS) {
            saveWithStock(bySku.get((String) r[0]), (Integer) r[7]);
        }
        log.info("Seeded {} demo products", PRODUCTS.length);
    }

    /** Supplies used by services (and gift boxes, also sold on their own), services, and gift sets. */
    private void seedItemsServicesAndPackages(Map<String, Category> cats) {
        Category supplies = category(cats, "Store Supplies");
        Category services = category(cats, "Services");
        Category gifts = category(cats, "Gift Sets");

        Offering box = stocked(OfferingType.ITEM, "SP-GBX-M", "Gift Box (Medium)", supplies, "1.50", "0.80", "pcs");
        box.setNameKm("ប្រអប់កាដូ (មធ្យម)");
        box.setSellable(true);
        box.setBarcode(ean13("884010200001"));
        box.getPacks().add(new OfferingPack(box, units.findBySymbol("pack").orElseThrow(), 25));
        saveWithStock(box, 60);
        Offering paper = saveWithStock(item("SP-WRP-01", "Gift Wrapping Paper Sheet", "ក្រដាសខ្ចប់កាដូ", supplies, "0.20", 5), 120);
        Offering ribbon = saveWithStock(item("SP-RBN-1M", "Satin Ribbon 1m", "ខ្សែបូ ១ម៉ែត្រ", supplies, "0.10", 3), 150);
        Offering thread = saveWithStock(item("SP-THR-01", "Embroidery Thread Spool", "អំបោះប៉ាក់", supplies, "0.60", 4), 4);
        saveWithStock(item("SP-BAG-PP", "Paper Shopping Bag", "ថង់ក្រដាស", supplies, "0.15", 50), 300);

        Offering wrap = service("SV-GFT-01", "Gift Wrapping", services, "2.00", PricingMode.FIXED, 10, paper, 1);
        wrap.setNameKm("សេវាខ្ចប់កាដូ");
        wrap.getComponents().add(new ServiceComponent(wrap, ribbon, 1));
        offerings.save(wrap);
        Offering embroidery = service("SV-EMB-01", "Name Embroidery", services, "4.50", PricingMode.FIXED, 30, thread, 1);
        embroidery.setNameKm("ប៉ាក់ឈ្មោះលើសម្លៀកបំពាក់");
        offerings.save(embroidery);
        Offering hem = service("SV-ALT-01", "Hemming & Alteration", services, "3.00", PricingMode.FIXED, 20, null, 0);
        hem.setNameKm("កែខោអាវ");
        offerings.save(hem);
        Offering photo = service("SV-PHO-HR", "Baby Photo Session", services, "20.00", PricingMode.HOURLY, null, null, 0);
        photo.setNameKm("ថតរូបទារក");
        offerings.save(photo);

        offerings.save(giftSet("GS-NBN-01", "Newborn Gift Set", "ឈុតកាដូទារកទើបកើត", gifts, "29.90",
                "Bodysuits, knit hat, mittens and a swaddle blanket in a gift box",
                "BC-BOD-03", "BC-HAT-NB", "BC-MIT-NB", "BC-SWD-01", "SP-GBX-M"));
        offerings.save(giftSet("GS-BTH-01", "Bath Time Bundle", "ឈុតងូតទឹកទារក", gifts, "16.90",
                "Tear-free shampoo, gentle lotion and bath ducks",
                "CA-SHP-300", "CA-LOT-200", "TY-DCK-03"));
        log.info("Seeded demo supplies, services and gift sets");
    }

    private Offering item(String sku, String name, String nameKm, Category category, String cost, int lowStock) {
        Offering o = stocked(OfferingType.ITEM, sku, name, category, "0.00", cost, "pcs");
        o.setNameKm(nameKm);
        o.setLowStockThreshold(lowStock);
        return o;
    }

    /** A package of one of each given SKU. */
    private Offering giftSet(String sku, String name, String nameKm, Category category, String price, String description,
                             String... skus) {
        Offering pkg = new Offering(OfferingType.PACKAGE);
        pkg.setSku(sku);
        pkg.setName(name);
        pkg.setNameKm(nameKm);
        pkg.setDescription(description);
        pkg.setCategory(category);
        pkg.setPrice(new BigDecimal(price));
        pkg.setTaxRate(taxRates.findByDefaultRateTrue().orElseThrow());
        for (int i = 0; i < skus.length; i++) {
            Offering part = offerings.findBySku(skus[i]).orElseThrow();
            pkg.getPackageLines().add(new PackageLine(pkg, part, BigDecimal.ONE, part.getUnit(), i));
        }
        return pkg;
    }

    /** Adds the EAN-13 check digit to 12 digits. */
    static String ean13(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (twelve.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return twelve + (10 - sum % 10) % 10;
    }

    /** Saves an entry and puts its demo stock in the main branch's selling storage. */
    private Offering saveWithStock(Offering o, int quantity) {
        offerings.saveAndFlush(o);
        Long selling = storages.findByBranchIdAndSellingTrue(
                branches.findAll().stream().filter(b -> b.getCode().equals("MAIN")).findFirst().orElseThrow().getId())
                .orElseThrow().getId();
        stock.add(selling, o.getId(), quantity, MovementType.OPENING, new StockService.Ref(null, null, null, "Demo data"));
        return o;
    }

    private Category category(Map<String, Category> cats, String name) {
        return cats.computeIfAbsent(name, n -> categories.save(new Category(n)));
    }

    private Offering stocked(OfferingType type, String sku, String name, Category category, String price,
                             String cost, String unit) {
        Offering o = new Offering(type);
        o.setSku(sku);
        o.setName(name);
        o.setCategory(category);
        o.setPrice(new BigDecimal(price));
        o.setCost(new BigDecimal(cost));
        o.setUnit(units.findBySymbol(unit).orElseThrow());
        o.setTaxRate(taxRates.findByDefaultRateTrue().orElseThrow());
        if (type == OfferingType.ITEM) {
            o.setSellable(false);
        }
        return o;
    }

    private Offering service(String sku, String name, Category category, String price, PricingMode pricing,
                                    Integer minutes, Offering part, int partQuantity) {
        Offering s = new Offering(OfferingType.SERVICE);
        s.setSku(sku);
        s.setName(name);
        s.setCategory(category);
        s.setPrice(new BigDecimal(price));
        s.setPricing(pricing);
        s.setTaxRate(taxRates.findByDefaultRateTrue().orElseThrow());
        s.setDurationMinutes(minutes);
        if (part != null) {
            s.getComponents().add(new ServiceComponent(s, part, partQuantity));
        }
        return s;
    }

    private Role role(String code) {
        return roles.findByCode(code).orElseThrow();
    }

    private static com.pos.purchasing.Vendor vendor(String code, String name, String contact, String phone, int terms) {
        var v = new com.pos.purchasing.Vendor();
        v.setCode(code);
        v.setName(name);
        v.setContactName(contact);
        v.setPhone(phone);
        v.setPaymentTermsDays(terms);
        return v;
    }
}
