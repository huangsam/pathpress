#!/usr/bin/env python3
"""
PathPress Location Pair Matrix Definitions.

Defines the MatrixTestCase data structure and route matrices for all 50 US States
and Washington, D.C., organized geographically from West to East across 7 regions.
Each state contains 5-7 verified OSM location pairs covering major corridors,
cross-state traversals, scenic/national park routes, regional links, and raw coordinates.
"""

from dataclasses import dataclass


@dataclass
class MatrixTestCase:
    id: int
    category: str
    start: str
    end: str
    days: int
    units: str


# ==============================================================================
# REGION 1: PACIFIC & WEST COAST (WA, OR, CA, AK, HI)
# ==============================================================================

WASHINGTON_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Seattle, WA", "Spokane, WA", 1, "imperial"),
    MatrixTestCase(2, "Cross-State", "Olympia, WA", "Pullman, WA", 2, "metric"),
    MatrixTestCase(3, "Scenic / Cascades", "Bellingham, WA", "Yakima, WA", 1, "imperial"),
    MatrixTestCase(4, "National Parks Gateway", "Ashford, WA", "Port Angeles, WA", 2, "metric"),
    MatrixTestCase(5, "Puget Sound Corridor", "Tacoma, WA", "Vancouver, WA", 1, "imperial"),
    MatrixTestCase(6, "Raw Coordinates", "47.6062,-122.3321", "47.6588,-117.4260", 1, "metric"),
]

OREGON_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Portland, OR", "Eugene, OR", 1, "metric"),
    MatrixTestCase(2, "Cross-State", "Astoria, OR", "Bend, OR", 2, "imperial"),
    MatrixTestCase(3, "Scenic / Coast", "Cannon Beach, OR", "Newport, OR", 1, "metric"),
    MatrixTestCase(4, "Mountain / Park", "Hood River, OR", "Crater Lake, OR", 2, "imperial"),
    MatrixTestCase(5, "Southern Corridor", "Medford, OR", "Klamath Falls, OR", 1, "metric"),
    MatrixTestCase(6, "Raw Coordinates", "45.5152,-122.6784", "44.0521,-123.0868", 1, "imperial"),
]

CALIFORNIA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metropolitan", "San Francisco", "Los Angeles", 2, "metric"),
    MatrixTestCase(2, "Major Metropolitan", "Sacramento", "San Diego", 3, "imperial"),
    MatrixTestCase(3, "Coastal & Highway 1", "Monterey", "Morro Bay", 1, "metric"),
    MatrixTestCase(4, "Coastal & Highway 1", "Santa Cruz, CA", "Big Sur, CA", 1, "imperial"),
    MatrixTestCase(5, "Mountain / Nat'l Parks", "Yosemite National Park, CA", "Lake Tahoe, CA", 2, "metric"),
    MatrixTestCase(6, "Mountain / Nat'l Parks", "Lake Tahoe", "Mount Shasta", 2, "imperial"),
    MatrixTestCase(7, "Mountain / Nat'l Parks", "Mount Shasta", "Mammoth Lakes", 3, "metric"),
    MatrixTestCase(8, "Desert / Remote", "Death Valley", "Joshua Tree", 2, "metric"),
    MatrixTestCase(9, "Desert / Remote", "Palm Springs", "Borrego Springs", 1, "imperial"),
    MatrixTestCase(10, "Raw Coordinates", "37.7749,-122.4194", "34.0537,-118.2427", 1, "metric"),
    MatrixTestCase(11, "Raw Coordinates", "32.7157,-117.1611", "38.5816,-121.4944", 3, "imperial"),
    MatrixTestCase(12, "Multi-day (5-day)", "San Francisco", "San Diego", 5, "metric"),
]

ALASKA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Highway Corridor", "Anchorage, AK", "Fairbanks, AK", 2, "imperial"),
    MatrixTestCase(2, "Scenic / Kenai", "Seward, AK", "Homer, AK", 1, "metric"),
    MatrixTestCase(3, "Interior / National Park", "Denali National Park, AK", "Valdez, AK", 2, "imperial"),
    MatrixTestCase(4, "Regional Corridor", "Kenai, AK", "Palmer, AK", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "61.2181,-149.9003", "64.8378,-147.7164", 2, "metric"),
]

HAWAII_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Oahu Island Traverse", "Honolulu, HI", "Kailua, Honolulu County, HI", 1, "imperial"),
    MatrixTestCase(2, "North Shore Scenic", "Waikiki, HI", "Haleiwa, HI", 1, "metric"),
    MatrixTestCase(3, "Windward Coast", "Kaneohe, HI", "Laie, HI", 1, "imperial"),
    MatrixTestCase(4, "South Shore Corridor", "Honolulu, HI", "Kapolei, HI", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "21.3069,-157.8583", "21.5928,-158.1033", 1, "imperial"),
]


# ==============================================================================
# REGION 2: MOUNTAIN WEST & SOUTHWEST (NV, ID, UT, AZ, MT, WY, CO, NM)
# ==============================================================================

NEVADA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Corridor", "Las Vegas, NV", "Reno, NV", 2, "imperial"),
    MatrixTestCase(2, "Capital & Sierra", "Carson City, NV", "Elko, NV", 1, "metric"),
    MatrixTestCase(3, "Scenic / Lake", "Henderson, NV", "Incline Village, NV", 2, "imperial"),
    MatrixTestCase(4, "Rural Highway", "Tonopah, NV", "Ely, NV", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "36.1699,-115.1398", "39.5296,-119.8138", 2, "metric"),
]

IDAHO_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Boise, ID", "Idaho Falls, ID", 1, "metric"),
    MatrixTestCase(2, "Panhandle Traversal", "Boise, ID", "Coeur d'Alene, ID", 2, "imperial"),
    MatrixTestCase(3, "Mountain / Resort", "Sun Valley, ID", "McCall, ID", 1, "metric"),
    MatrixTestCase(4, "Southern Corridor", "Twin Falls, ID", "Pocatello, ID", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "43.6150,-116.2023", "47.6777,-116.7805", 2, "metric"),
]

UTAH_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Wasatch Front Metro", "Salt Lake City, UT", "Provo, UT", 1, "imperial"),
    MatrixTestCase(2, "Red Rock / National Parks", "Moab, UT", "Springdale, UT", 2, "metric"),
    MatrixTestCase(3, "Cross-State Traversal", "Logan, UT", "St. George, UT", 2, "imperial"),
    MatrixTestCase(4, "Scenic / Mountain", "Park City, UT", "Torrey, UT", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "40.7608,-111.8910", "37.0965,-113.5684", 2, "imperial"),
]

ARIZONA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Phoenix, AZ", "Tucson, AZ", 1, "imperial"),
    MatrixTestCase(2, "Mountain / Canyon", "Flagstaff, AZ", "Grand Canyon Village, AZ", 1, "metric"),
    MatrixTestCase(3, "Cross-State Traversal", "Phoenix, AZ", "Flagstaff, AZ", 1, "metric"),
    MatrixTestCase(4, "Red Rock / Scenic", "Sedona, AZ", "Yuma, AZ", 2, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "33.4484,-112.0740", "32.2226,-110.9747", 1, "metric"),
]

MONTANA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Corridor", "Billings, MT", "Missoula, MT", 2, "imperial"),
    MatrixTestCase(2, "Glacier / Mountain", "Bozeman, MT", "Whitefish, MT", 2, "metric"),
    MatrixTestCase(3, "Capital Corridor", "Helena, MT", "Great Falls, MT", 1, "imperial"),
    MatrixTestCase(4, "Flathead / Western", "Kalispell, MT", "Butte, MT", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "45.7833,-108.5007", "46.8722,-113.9940", 2, "imperial"),
]

WYOMING_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Capital Corridor", "Cheyenne, WY", "Casper, WY", 1, "imperial"),
    MatrixTestCase(2, "Yellowstone / Teton", "Jackson, WY", "Cody, WY", 1, "metric"),
    MatrixTestCase(3, "Cross-State Traversal", "Cheyenne, WY", "Jackson, WY", 2, "imperial"),
    MatrixTestCase(4, "Mountain / Foothills", "Laramie, WY", "Sheridan, WY", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "41.1400,-104.8202", "43.4799,-110.7624", 2, "metric"),
]

COLORADO_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Front Range Metro", "Denver, CO", "Colorado Springs, CO", 1, "metric"),
    MatrixTestCase(2, "Mountain Ski Corridor", "Denver, CO", "Vail, CO", 1, "imperial"),
    MatrixTestCase(3, "Cross-State Rockies", "Boulder, CO", "Grand Junction, CO", 2, "metric"),
    MatrixTestCase(4, "San Juan Scenic", "Aspen, CO", "Durango, CO", 2, "imperial"),
    MatrixTestCase(5, "Northern Corridor", "Fort Collins, CO", "Pueblo, CO", 1, "metric"),
    MatrixTestCase(6, "Raw Coordinates", "39.7392,-104.9903", "38.8339,-104.8214", 1, "imperial"),
]

NEW_MEXICO_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Albuquerque, NM", "Santa Fe, NM", 1, "metric"),
    MatrixTestCase(2, "Cross-State Traversal", "Albuquerque, NM", "Las Cruces, NM", 2, "imperial"),
    MatrixTestCase(3, "Enchanted Circle", "Santa Fe, NM", "Taos, NM", 1, "imperial"),
    MatrixTestCase(4, "Southeastern Corridor", "Roswell, NM", "Carlsbad, NM", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "35.0844,-106.6504", "35.6870,-105.9378", 1, "metric"),
]


# ==============================================================================
# REGION 3: GREAT PLAINS & SOUTH CENTRAL (ND, SD, NE, KS, OK, TX)
# ==============================================================================

NORTH_DAKOTA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Corridor", "Fargo, ND", "Bismarck, ND", 1, "imperial"),
    MatrixTestCase(2, "Northern Cross-State", "Grand Forks, ND", "Minot, ND", 1, "metric"),
    MatrixTestCase(3, "Badlands / Scenic", "Bismarck, ND", "Medora, ND", 1, "imperial"),
    MatrixTestCase(4, "Energy Corridor", "Jamestown, ND", "Williston, ND", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "46.8772,-96.7898", "46.8083,-100.7837", 1, "imperial"),
]

SOUTH_DAKOTA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Corridor", "Sioux Falls, SD", "Rapid City, SD", 2, "imperial"),
    MatrixTestCase(2, "Capital Corridor", "Pierre, SD", "Sioux Falls, SD", 1, "metric"),
    MatrixTestCase(3, "Black Hills Scenic", "Rapid City, SD", "Deadwood, SD", 1, "imperial"),
    MatrixTestCase(4, "Glacial Lakes", "Aberdeen, SD", "Watertown, SD", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "43.5460,-96.7313", "44.0805,-103.2310", 2, "metric"),
]

NEBRASKA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Omaha, NE", "Lincoln, NE", 1, "metric"),
    MatrixTestCase(2, "I-80 Traversal", "Omaha, NE", "North Platte, NE", 2, "imperial"),
    MatrixTestCase(3, "Panhandle Route", "Grand Island, NE", "Scottsbluff, NE", 2, "metric"),
    MatrixTestCase(4, "Platte Valley", "Lincoln, NE", "Kearney, NE", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "41.2565,-95.9345", "40.8136,-96.7026", 1, "imperial"),
]

KANSAS_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Wichita, KS", "Topeka, KS", 1, "imperial"),
    MatrixTestCase(2, "Flint Hills Route", "Kansas City, KS", "Wichita, KS", 1, "metric"),
    MatrixTestCase(3, "I-70 Traversal", "Topeka, KS", "Colby, KS", 2, "imperial"),
    MatrixTestCase(4, "University Corridor", "Lawrence, KS", "Salina, KS", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "37.6872,-97.3301", "39.0558,-95.6890", 1, "metric"),
]

OKLAHOMA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Turnpike Metro", "Oklahoma City, OK", "Tulsa, OK", 1, "imperial"),
    MatrixTestCase(2, "Cross-State North-South", "Lawton, OK", "Enid, OK", 1, "metric"),
    MatrixTestCase(3, "Ouachita Scenic", "Norman, OK", "Broken Bow, OK", 2, "imperial"),
    MatrixTestCase(4, "Panhandle Link", "Oklahoma City, OK", "Guymon, OK", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "35.4676,-97.5164", "36.1540,-95.9928", 1, "metric"),
]

TEXAS_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Austin, TX", "San Antonio, TX", 1, "metric"),
    MatrixTestCase(2, "Major Metro", "Houston, TX", "Dallas, TX", 1, "imperial"),
    MatrixTestCase(3, "Major Metro", "Dallas, TX", "Austin, TX", 1, "metric"),
    MatrixTestCase(4, "Multi-day Traversal", "Houston, TX", "El Paso, TX", 3, "imperial"),
    MatrixTestCase(5, "Multi-day Traversal", "San Antonio, TX", "Amarillo, TX", 2, "metric"),
    MatrixTestCase(6, "Multi-day Traversal", "Dallas, TX", "Corpus Christi, TX", 2, "imperial"),
    MatrixTestCase(7, "Hill Country / Scenic", "Austin, TX", "Fredericksburg, TX", 1, "imperial"),
    MatrixTestCase(8, "Coastal Drive", "Corpus Christi, TX", "Galveston, TX", 1, "metric"),
    MatrixTestCase(9, "Raw Coordinates", "30.2711,-97.7437", "29.4246,-98.4951", 1, "metric"),
]


# ==============================================================================
# REGION 4: MIDWEST & GREAT LAKES (MN, IA, MO, WI, IL, MI, IN, OH)
# ==============================================================================

MINNESOTA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Minneapolis, MN", "Duluth, MN", 1, "imperial"),
    MatrixTestCase(2, "Southern Corridor", "Saint Paul, MN", "Rochester, MN", 1, "metric"),
    MatrixTestCase(3, "North Shore Scenic", "Duluth, MN", "Grand Marais, MN", 1, "imperial"),
    MatrixTestCase(4, "Prairie Cross-State", "Saint Cloud, MN", "Moorhead, MN", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "44.9778,-93.2650", "46.7867,-92.1005", 1, "metric"),
]

IOWA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Des Moines, IA", "Cedar Rapids, IA", 1, "metric"),
    MatrixTestCase(2, "I-80 Traversal", "Davenport, IA", "Council Bluffs, IA", 2, "imperial"),
    MatrixTestCase(3, "University Corridor", "Iowa City, IA", "Ames, IA", 1, "metric"),
    MatrixTestCase(4, "Mississippi River", "Dubuque, IA", "Waterloo, IA", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "41.5868,-93.6250", "41.9779,-91.6656", 1, "imperial"),
]

MISSOURI_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Saint Louis, MO", "Kansas City, MO", 1, "imperial"),
    MatrixTestCase(2, "Ozarks Scenic", "Springfield, MO", "Branson, MO", 1, "metric"),
    MatrixTestCase(3, "Capital & University", "Jefferson City, MO", "Columbia, MO", 1, "imperial"),
    MatrixTestCase(4, "Cross-State Southwest", "Saint Louis, MO", "Joplin, MO", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "38.6270,-90.1994", "39.0997,-94.5786", 1, "metric"),
]

WISCONSIN_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Milwaukee, WI", "Madison, WI", 1, "metric"),
    MatrixTestCase(2, "Door County Scenic", "Green Bay, WI", "Sturgeon Bay, WI", 1, "imperial"),
    MatrixTestCase(3, "Cross-State Northwest", "Madison, WI", "Eau Claire, WI", 2, "imperial"),
    MatrixTestCase(4, "Fox Valley & North", "Appleton, WI", "Wausau, WI", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "43.0389,-87.9065", "43.0731,-89.4012", 1, "metric"),
]

ILLINOIS_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Chicago, IL", "Springfield, IL", 1, "imperial"),
    MatrixTestCase(2, "Northern Corridor", "Chicago, IL", "Rockford, IL", 1, "metric"),
    MatrixTestCase(3, "Central Prairie", "Peoria, IL", "Champaign, IL", 1, "imperial"),
    MatrixTestCase(4, "Cross-State South", "Chicago, IL", "Carbondale, IL", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "41.8781,-87.6298", "39.7817,-89.6501", 1, "imperial"),
]

MICHIGAN_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Detroit, MI", "Grand Rapids, MI", 1, "metric"),
    MatrixTestCase(2, "Upper Peninsula", "Mackinaw City, MI", "Marquette, MI", 1, "imperial"),
    MatrixTestCase(3, "Capital & Lake Scenic", "Lansing, MI", "Traverse City, MI", 2, "imperial"),
    MatrixTestCase(4, "West Coast Beach", "Kalamazoo, MI", "Muskegon, MI", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "42.3314,-83.0458", "42.9634,-85.6681", 1, "imperial"),
]

INDIANA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Indianapolis, IN", "Fort Wayne, IN", 1, "imperial"),
    MatrixTestCase(2, "University Route", "Indianapolis, IN", "Bloomington, IN", 1, "metric"),
    MatrixTestCase(3, "North-South Cross-State", "South Bend, IN", "Evansville, IN", 2, "imperial"),
    MatrixTestCase(4, "Central Valley", "Lafayette, IN", "Terre Haute, IN", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "39.7684,-86.1581", "41.0793,-85.1394", 1, "metric"),
]

OHIO_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "3C Corridor North", "Columbus, OH", "Cleveland, OH", 1, "imperial"),
    MatrixTestCase(2, "3C Corridor South", "Columbus, OH", "Cincinnati, OH", 1, "metric"),
    MatrixTestCase(3, "Western Corridor", "Toledo, OH", "Dayton, OH", 1, "imperial"),
    MatrixTestCase(4, "Appalachian Foothills", "Akron, OH", "Athens, OH", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "39.9612,-82.9988", "41.4993,-81.6944", 1, "imperial"),
]


# ==============================================================================
# REGION 5: SOUTHEAST & GULF COAST (AR, LA, MS, AL, TN, KY, GA, FL, SC, NC)
# ==============================================================================

ARKANSAS_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Little Rock, AR", "Fayetteville, AR", 1, "imperial"),
    MatrixTestCase(2, "Hot Springs Scenic", "Little Rock, AR", "Hot Springs, AR", 1, "metric"),
    MatrixTestCase(3, "Ozark Cross-State", "Fort Smith, AR", "Jonesboro, AR", 2, "imperial"),
    MatrixTestCase(4, "Delta to Piney Woods", "Pine Bluff, AR", "Texarkana, AR", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "34.7465,-92.2896", "36.0822,-94.1719", 1, "metric"),
]

LOUISIANA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "New Orleans, LA", "Baton Rouge, LA", 1, "metric"),
    MatrixTestCase(2, "Cajun Country", "Baton Rouge, LA", "Lafayette, LA", 1, "imperial"),
    MatrixTestCase(3, "Cross-State Diagonal", "New Orleans, LA", "Shreveport, LA", 2, "imperial"),
    MatrixTestCase(4, "Bayou & Gulf Coast", "Lake Charles, LA", "Houma, LA", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "29.9511,-90.0715", "30.4515,-91.1871", 1, "imperial"),
]

MISSISSIPPI_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Jackson, MS", "Gulfport, MS", 1, "imperial"),
    MatrixTestCase(2, "Delta to Oxford", "Jackson, MS", "Oxford, MS", 1, "metric"),
    MatrixTestCase(3, "Historic River Road", "Vicksburg, MS", "Natchez, MS", 1, "imperial"),
    MatrixTestCase(4, "Eastern Corridor", "Tupelo, MS", "Hattiesburg, MS", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "32.2988,-90.1848", "30.3674,-89.0928", 1, "metric"),
]

ALABAMA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Birmingham, AL", "Montgomery, AL", 1, "imperial"),
    MatrixTestCase(2, "Rocket City Corridor", "Birmingham, AL", "Huntsville, AL", 1, "metric"),
    MatrixTestCase(3, "Gulf Coast Route", "Montgomery, AL", "Mobile, AL", 1, "imperial"),
    MatrixTestCase(4, "Cross-State Spine", "Mobile, AL", "Huntsville, AL", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "33.5186,-86.8104", "32.3792,-86.3077", 1, "imperial"),
]

TENNESSEE_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Nashville, TN", "Memphis, TN", 1, "imperial"),
    MatrixTestCase(2, "I-40 East Corridor", "Nashville, TN", "Knoxville, TN", 1, "metric"),
    MatrixTestCase(3, "Smoky Mountains", "Knoxville, TN", "Gatlinburg, TN", 1, "imperial"),
    MatrixTestCase(4, "Scenic Valley", "Chattanooga, TN", "Johnson City, TN", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "36.1627,-86.7816", "35.1495,-90.0490", 1, "metric"),
]

KENTUCKY_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Louisville, KY", "Lexington, KY", 1, "metric"),
    MatrixTestCase(2, "Bluegrass Corridor", "Frankfort, KY", "Bowling Green, KY", 1, "imperial"),
    MatrixTestCase(3, "Western Waterways", "Mammoth Cave, KY", "Paducah, KY", 1, "metric"),
    MatrixTestCase(4, "Appalachian Highlands", "Lexington, KY", "Pikeville, KY", 2, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "38.2527,-85.7585", "38.0406,-84.5037", 1, "imperial"),
]

GEORGIA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro & Coast", "Atlanta, GA", "Savannah, GA", 2, "imperial"),
    MatrixTestCase(2, "University Corridor", "Atlanta, GA", "Athens, GA", 1, "metric"),
    MatrixTestCase(3, "Central Fall Line", "Macon, GA", "Augusta, GA", 1, "imperial"),
    MatrixTestCase(4, "North Mountains to Coast", "Blue Ridge, GA", "Brunswick, GA", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "33.7490,-84.3880", "32.0809,-81.0912", 1, "metric"),
]

FLORIDA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Miami, FL", "Orlando, FL", 1, "imperial"),
    MatrixTestCase(2, "Gulf Coast Corridor", "Tampa, FL", "Naples, FL", 1, "metric"),
    MatrixTestCase(3, "Overseas Highway", "Miami, FL", "Key West, FL", 2, "imperial"),
    MatrixTestCase(4, "Panhandle Capital", "Jacksonville, FL", "Tallahassee, FL", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "25.7617,-80.1918", "28.5383,-81.3792", 1, "imperial"),
]

SOUTH_CAROLINA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Capital to Coast", "Columbia, SC", "Charleston, SC", 1, "metric"),
    MatrixTestCase(2, "Upstate to Grand Strand", "Greenville, SC", "Myrtle Beach, SC", 2, "imperial"),
    MatrixTestCase(3, "Lowcountry Scenic", "Charleston, SC", "Hilton Head Island, SC", 1, "metric"),
    MatrixTestCase(4, "Piedmont Corridor", "Rock Hill, SC", "Florence, SC", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "34.0007,-81.0348", "32.7765,-79.9311", 1, "imperial"),
]

NORTH_CAROLINA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Piedmont Metro", "Raleigh, NC", "Charlotte, NC", 1, "imperial"),
    MatrixTestCase(2, "Blue Ridge Mountains", "Asheville, NC", "Boone, NC", 1, "metric"),
    MatrixTestCase(3, "Coastal Outer Banks", "Wilmington, NC", "Nags Head, NC", 2, "imperial"),
    MatrixTestCase(4, "Triad Corridor", "Greensboro, NC", "Winston-Salem, NC", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "35.7796,-78.6382", "35.2271,-80.8431", 1, "metric"),
]


# ==============================================================================
# REGION 6: MID-ATLANTIC (VA, WV, MD, DE, DC, PA, NJ)
# ==============================================================================

VIRGINIA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Capital to Coast", "Richmond, VA", "Virginia Beach, VA", 1, "metric"),
    MatrixTestCase(2, "Shenandoah Valley", "Alexandria, VA", "Roanoke, VA", 2, "imperial"),
    MatrixTestCase(3, "Central Piedmont", "Charlottesville, VA", "Harrisonburg, VA", 1, "metric"),
    MatrixTestCase(4, "Southwest Blue Ridge", "Lynchburg, VA", "Bristol, VA", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "37.5407,-77.4360", "36.8529,-75.9780", 1, "metric"),
]

WEST_VIRGINIA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Capital Corridor", "Charleston, WV", "Huntington, WV", 1, "imperial"),
    MatrixTestCase(2, "Mountain Corridor", "Morgantown, WV", "Charleston, WV", 1, "metric"),
    MatrixTestCase(3, "New River Gorge", "Beckley, WV", "Fayetteville, WV", 1, "imperial"),
    MatrixTestCase(4, "Panhandle to Ohio River", "Martinsburg, WV", "Wheeling, WV", 2, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "38.3498,-81.6326", "39.6295,-79.9559", 1, "metric"),
]

MARYLAND_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Major Metro", "Baltimore, MD", "Annapolis, MD", 1, "metric"),
    MatrixTestCase(2, "Eastern Shore Route", "Annapolis, MD", "Ocean City, MD", 1, "imperial"),
    MatrixTestCase(3, "Western Mountains", "Frederick, MD", "Cumberland, MD", 1, "metric"),
    MatrixTestCase(4, "Capital Beltway Suburbs", "Silver Spring, MD", "Bethesda, MD", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "39.2904,-76.6122", "38.9784,-76.4922", 1, "imperial"),
]

DELAWARE_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "North-South Spine", "Wilmington, DE", "Dover, DE", 1, "imperial"),
    MatrixTestCase(2, "Coastal Route", "Dover, DE", "Rehoboth Beach, DE", 1, "metric"),
    MatrixTestCase(3, "Historic Bay Route", "Newark, DE", "Lewes, DE", 1, "imperial"),
    MatrixTestCase(4, "Southern Farmland", "Middletown, DE", "Seaford, DE", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "39.7447,-75.5484", "39.1582,-75.5244", 1, "imperial"),
]

DISTRICT_OF_COLUMBIA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Mall Traverse", "Georgetown, Washington, DC", "Capitol Hill, Washington, DC", 1, "imperial"),
    MatrixTestCase(2, "East-West Crossing", "Foggy Bottom, Washington, DC", "Anacostia, Washington, DC", 1, "metric"),
    MatrixTestCase(3, "Corridor Traverse", "Georgetown, Washington, DC", "Anacostia, Washington, DC", 1, "imperial"),
    MatrixTestCase(4, "Cross-City Route", "38.8991,-77.0547", "38.8622,-76.9953", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "38.9089,-77.0746", "38.8898,-77.0094", 1, "metric"),
]


PENNSYLVANIA_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Turnpike Cross-State", "Philadelphia, PA", "Pittsburgh, PA", 2, "imperial"),
    MatrixTestCase(2, "Capital Corridor", "Harrisburg, PA", "Lancaster, PA", 1, "metric"),
    MatrixTestCase(3, "Lehigh to Poconos", "Allentown, PA", "Scranton, PA", 1, "imperial"),
    MatrixTestCase(4, "Lake Erie Corridor", "Pittsburgh, PA", "Erie, PA", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "39.9526,-75.1652", "40.4406,-79.9959", 2, "metric"),
]

NEW_JERSEY_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Garden State Parkway", "Newark, NJ", "Atlantic City, NJ", 1, "imperial"),
    MatrixTestCase(2, "Capital & University", "Trenton, NJ", "Princeton, NJ", 1, "metric"),
    MatrixTestCase(3, "Jersey Shore Drive", "Asbury Park, NJ", "Cape May, NJ", 1, "imperial"),
    MatrixTestCase(4, "Northern Highlands", "Jersey City, NJ", "Morristown, NJ", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "40.7357,-74.1724", "39.3643,-74.4229", 1, "metric"),
]


# ==============================================================================
# REGION 7: NORTHEAST & NEW ENGLAND (NY, CT, RI, MA, VT, NH, ME)
# ==============================================================================

NEW_YORK_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Empire Corridor", "New York, NY", "Albany, NY", 1, "imperial"),
    MatrixTestCase(2, "Thruway Traversal", "Albany, NY", "Buffalo, NY", 2, "metric"),
    MatrixTestCase(3, "Adirondacks Scenic", "Saratoga Springs, NY", "Lake Placid, NY", 1, "imperial"),
    MatrixTestCase(4, "Finger Lakes Route", "Syracuse, NY", "Ithaca, NY", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "40.7128,-74.0060", "42.6526,-73.7562", 1, "imperial"),
]

CONNECTICUT_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Coastal Corridor", "Stamford, CT", "New Haven, CT", 1, "imperial"),
    MatrixTestCase(2, "Capital Corridor", "New Haven, CT", "Hartford, CT", 1, "metric"),
    MatrixTestCase(3, "Mystic Coast Route", "Hartford, CT", "Mystic, CT", 1, "imperial"),
    MatrixTestCase(4, "Litchfield Hills", "Waterbury, CT", "Torrington, CT", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "41.3083,-72.9279", "41.7658,-72.6734", 1, "imperial"),
]

RHODE_ISLAND_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Capital to Coast", "Providence, RI", "Newport, RI", 1, "imperial"),
    MatrixTestCase(2, "South County Coast", "Warwick, RI", "Narragansett, RI", 1, "metric"),
    MatrixTestCase(3, "East Bay Scenic", "Bristol, RI", "Little Compton, RI", 1, "imperial"),
    MatrixTestCase(4, "Blackstone Valley", "Woonsocket, RI", "Providence, RI", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "41.8240,-71.4128", "41.4901,-71.3128", 1, "metric"),
]

MASSACHUSETTS_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Mass Pike East", "Boston, MA", "Worcester, MA", 1, "imperial"),
    MatrixTestCase(2, "Mass Pike West", "Boston, MA", "Springfield, MA", 1, "metric"),
    MatrixTestCase(3, "Cape Cod Scenic", "Plymouth, MA", "Provincetown, MA", 1, "imperial"),
    MatrixTestCase(4, "Berkshires Route", "Northampton, MA", "Pittsfield, MA", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "42.3601,-71.0589", "42.2626,-71.8023", 1, "metric"),
]

VERMONT_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Lake to Capital", "Burlington, VT", "Montpelier, VT", 1, "metric"),
    MatrixTestCase(2, "Green Mountains", "Stowe, VT", "Manchester, VT", 1, "imperial"),
    MatrixTestCase(3, "Route 100 Scenic", "Waterbury, VT", "Killington, VT", 1, "metric"),
    MatrixTestCase(4, "Northeast Kingdom", "Saint Johnsbury, VT", "Newport, VT", 1, "imperial"),
    MatrixTestCase(5, "Raw Coordinates", "44.4759,-73.2121", "44.2601,-72.5754", 1, "imperial"),
]

NEW_HAMPSHIRE_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Capital Metro", "Manchester, NH", "Concord, NH", 1, "imperial"),
    MatrixTestCase(2, "White Mountains", "Concord, NH", "North Conway, NH", 1, "metric"),
    MatrixTestCase(3, "Seacoast Route", "Portsmouth, NH", "Dover, NH", 1, "imperial"),
    MatrixTestCase(4, "Lakes to Dartmouth", "Laconia, NH", "Hanover, NH", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "42.9956,-71.4548", "43.2081,-71.5376", 1, "metric"),
]

MAINE_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Coastal Corridor", "Portland, ME", "Augusta, ME", 1, "imperial"),
    MatrixTestCase(2, "Downeast Acadia", "Bangor, ME", "Bar Harbor, ME", 1, "metric"),
    MatrixTestCase(3, "Midcoast Scenic", "Brunswick, ME", "Camden, ME", 1, "imperial"),
    MatrixTestCase(4, "North Woods Route", "Augusta, ME", "Greenville, ME", 1, "metric"),
    MatrixTestCase(5, "Raw Coordinates", "43.6591,-70.2568", "44.3106,-69.7795", 1, "metric"),
]


# ==============================================================================
# COMPLETE STATE & REGIONAL LOOKUP DICTIONARIES
# ==============================================================================

# 50 States + DC Lookup Dictionary (supporting hyphenated and underscore keys)
STATE_MATRICES: dict[str, list[MatrixTestCase]] = {
    # Region 1: Pacific / West Coast
    "washington": WASHINGTON_MATRIX,
    "oregon": OREGON_MATRIX,
    "california": CALIFORNIA_MATRIX,
    "alaska": ALASKA_MATRIX,
    "hawaii": HAWAII_MATRIX,
    # Region 2: Mountain West & Southwest
    "nevada": NEVADA_MATRIX,
    "idaho": IDAHO_MATRIX,
    "utah": UTAH_MATRIX,
    "arizona": ARIZONA_MATRIX,
    "montana": MONTANA_MATRIX,
    "wyoming": WYOMING_MATRIX,
    "colorado": COLORADO_MATRIX,
    "new-mexico": NEW_MEXICO_MATRIX,
    "new_mexico": NEW_MEXICO_MATRIX,
    # Region 3: Great Plains & South Central
    "north-dakota": NORTH_DAKOTA_MATRIX,
    "north_dakota": NORTH_DAKOTA_MATRIX,
    "south-dakota": SOUTH_DAKOTA_MATRIX,
    "south_dakota": SOUTH_DAKOTA_MATRIX,
    "nebraska": NEBRASKA_MATRIX,
    "kansas": KANSAS_MATRIX,
    "oklahoma": OKLAHOMA_MATRIX,
    "texas": TEXAS_MATRIX,
    # Region 4: Midwest & Great Lakes
    "minnesota": MINNESOTA_MATRIX,
    "iowa": IOWA_MATRIX,
    "missouri": MISSOURI_MATRIX,
    "wisconsin": WISCONSIN_MATRIX,
    "illinois": ILLINOIS_MATRIX,
    "michigan": MICHIGAN_MATRIX,
    "indiana": INDIANA_MATRIX,
    "ohio": OHIO_MATRIX,
    # Region 5: Southeast & Gulf Coast
    "arkansas": ARKANSAS_MATRIX,
    "louisiana": LOUISIANA_MATRIX,
    "mississippi": MISSISSIPPI_MATRIX,
    "alabama": ALABAMA_MATRIX,
    "tennessee": TENNESSEE_MATRIX,
    "kentucky": KENTUCKY_MATRIX,
    "georgia": GEORGIA_MATRIX,
    "florida": FLORIDA_MATRIX,
    "south-carolina": SOUTH_CAROLINA_MATRIX,
    "south_carolina": SOUTH_CAROLINA_MATRIX,
    "north-carolina": NORTH_CAROLINA_MATRIX,
    "north_carolina": NORTH_CAROLINA_MATRIX,
    # Region 6: Mid-Atlantic
    "virginia": VIRGINIA_MATRIX,
    "west-virginia": WEST_VIRGINIA_MATRIX,
    "west_virginia": WEST_VIRGINIA_MATRIX,
    "maryland": MARYLAND_MATRIX,
    "delaware": DELAWARE_MATRIX,
    "district-of-columbia": DISTRICT_OF_COLUMBIA_MATRIX,
    "district_of_columbia": DISTRICT_OF_COLUMBIA_MATRIX,
    "pennsylvania": PENNSYLVANIA_MATRIX,
    "new-jersey": NEW_JERSEY_MATRIX,
    "new_jersey": NEW_JERSEY_MATRIX,
    # Region 7: Northeast & New England
    "new-york": NEW_YORK_MATRIX,
    "new_york": NEW_YORK_MATRIX,
    "connecticut": CONNECTICUT_MATRIX,
    "rhode-island": RHODE_ISLAND_MATRIX,
    "rhode_island": RHODE_ISLAND_MATRIX,
    "massachusetts": MASSACHUSETTS_MATRIX,
    "vermont": VERMONT_MATRIX,
    "new-hampshire": NEW_HAMPSHIRE_MATRIX,
    "new_hampshire": NEW_HAMPSHIRE_MATRIX,
    "maine": MAINE_MATRIX,
}


# ==============================================================================
# MULTI-STATE REGIONAL CORRIDOR MATRICES
# ==============================================================================

US_NORTHEAST_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Metro Corridor", "Boston, MA", "Philadelphia, PA", 6, "imperial"),
    MatrixTestCase(2, "Cross-State Traversal", "New York, NY", "Pittsburgh, PA", 3, "metric"),
    MatrixTestCase(3, "Coastal Corridor", "Portland, ME", "Atlantic City, NJ", 4, "imperial"),
    MatrixTestCase(4, "Coastal to Great Lakes", "Providence, RI", "Buffalo, NY", 3, "metric"),
    MatrixTestCase(5, "New England to Mid-State", "Hartford, CT", "Philadelphia, PA", 2, "imperial"),
    MatrixTestCase(6, "Raw Coordinates", "42.3588,-71.0578", "39.9527,-75.1635", 1, "metric"),
]

US_PACIFIC_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Alaska Highway Corridor", "Anchorage, AK", "Fairbanks, AK", 2, "imperial"),
    MatrixTestCase(2, "Kenai Peninsula Scenic", "Seward, AK", "Homer, AK", 2, "metric"),
    MatrixTestCase(3, "Interior / National Parks", "Denali National Park, AK", "Valdez, AK", 3, "imperial"),
    MatrixTestCase(4, "Oahu Island Traverse", "Honolulu, HI", "Kailua, Honolulu County, HI", 1, "metric"),
    MatrixTestCase(5, "Mat-Su to Glennallen", "Palmer, AK", "Glennallen, AK", 2, "imperial"),
    MatrixTestCase(6, "Raw Coordinates", "61.2181,-149.9003", "64.8378,-147.7164", 2, "metric"),
]

US_WEST_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "West Coast Corridor", "Seattle, WA", "San Francisco, CA", 5, "imperial"),
    MatrixTestCase(2, "Pacific Multi-State", "Portland, OR", "Los Angeles, CA", 6, "metric"),
    MatrixTestCase(3, "Pacific Border-to-Border", "San Diego, CA", "Seattle, WA", 7, "imperial"),
    MatrixTestCase(4, "Coast to Desert Corridor", "San Francisco, CA", "Las Vegas, NV", 3, "imperial"),
    MatrixTestCase(5, "Pacific NW to Rockies", "Seattle, WA", "Denver, CO", 5, "metric"),
    MatrixTestCase(6, "Great Basin to Desert SW", "Salt Lake City, UT", "Phoenix, AZ", 3, "imperial"),
]

US_MIDWEST_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Upper Midwest Corridor", "Chicago, IL", "Minneapolis, MN", 2, "imperial"),
    MatrixTestCase(2, "Great Lakes to Gateway", "Detroit, MI", "St. Louis, MO", 3, "metric"),
    MatrixTestCase(3, "Heartland Traversal", "Indianapolis, IN", "Kansas City, MO", 2, "imperial"),
    MatrixTestCase(4, "Great Lakes Shoreline", "Milwaukee, WI", "Cleveland, OH", 2, "metric"),
    MatrixTestCase(5, "Plains to Rust Belt", "Omaha, NE", "Columbus, OH", 3, "imperial"),
    MatrixTestCase(6, "Raw Coordinates", "41.8781,-87.6298", "44.9778,-93.2650", 1, "metric"),
]

US_SOUTH_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Deep South Corridor", "Atlanta, GA", "New Orleans, LA", 3, "imperial"),
    MatrixTestCase(2, "Music City to Hill Country", "Nashville, TN", "Austin, TX", 4, "metric"),
    MatrixTestCase(3, "Atlantic to Gulf Coast", "Miami, FL", "Charlotte, NC", 3, "imperial"),
    MatrixTestCase(4, "Mid-South to Lone Star", "Memphis, TN", "Dallas, TX", 2, "metric"),
    MatrixTestCase(5, "Coastal South Traverse", "Charleston, SC", "Houston, TX", 4, "imperial"),
    MatrixTestCase(6, "Raw Coordinates", "33.7490,-84.3880", "29.9511,-90.0715", 2, "metric"),
]

USA_NATIONWIDE_MATRIX: list[MatrixTestCase] = [
    MatrixTestCase(1, "Trans-Continental Diagonal", "Seattle, WA", "Miami, FL", 10, "metric"),
    MatrixTestCase(2, "Historic Coast-to-Coast", "New York, NY", "Los Angeles, CA", 8, "imperial"),
]

NATIONWIDE_MATRICES: dict[str, list[MatrixTestCase]] = {
    "usa": USA_NATIONWIDE_MATRIX,
    "us": USA_NATIONWIDE_MATRIX,
    "united-states": USA_NATIONWIDE_MATRIX,
    "nationwide": USA_NATIONWIDE_MATRIX,
}

REGIONAL_MATRICES: dict[str, list[MatrixTestCase]] = {
    "us-northeast": US_NORTHEAST_MATRIX,
    "us_northeast": US_NORTHEAST_MATRIX,
    "northeast": US_NORTHEAST_MATRIX,
    "us-pacific": US_PACIFIC_MATRIX,
    "us_pacific": US_PACIFIC_MATRIX,
    "pacific": US_PACIFIC_MATRIX,
    "us-west": US_WEST_MATRIX,
    "us_west": US_WEST_MATRIX,
    "west": US_WEST_MATRIX,
    "us-midwest": US_MIDWEST_MATRIX,
    "us_midwest": US_MIDWEST_MATRIX,
    "midwest": US_MIDWEST_MATRIX,
    "us-south": US_SOUTH_MATRIX,
    "us_south": US_SOUTH_MATRIX,
    "south": US_SOUTH_MATRIX,
    "usa": USA_NATIONWIDE_MATRIX,
    "us": USA_NATIONWIDE_MATRIX,
    "nationwide": USA_NATIONWIDE_MATRIX,
}

REGION_STATES: dict[str, list[str]] = {
    "us-northeast": [
        "connecticut",
        "maine",
        "massachusetts",
        "new-hampshire",
        "new-jersey",
        "new-york",
        "pennsylvania",
        "rhode-island",
        "vermont",
    ],
    "us_northeast": [
        "connecticut",
        "maine",
        "massachusetts",
        "new-hampshire",
        "new-jersey",
        "new-york",
        "pennsylvania",
        "rhode-island",
        "vermont",
    ],
    "northeast": [
        "connecticut",
        "maine",
        "massachusetts",
        "new-hampshire",
        "new-jersey",
        "new-york",
        "pennsylvania",
        "rhode-island",
        "vermont",
    ],
    "us-pacific": [
        "alaska",
        "hawaii",
    ],
    "us_pacific": [
        "alaska",
        "hawaii",
    ],
    "pacific": [
        "alaska",
        "hawaii",
    ],
    "us-west": [
        "arizona",
        "california",
        "colorado",
        "idaho",
        "montana",
        "nevada",
        "new-mexico",
        "oregon",
        "utah",
        "washington",
        "wyoming",
    ],
    "us_west": [
        "arizona",
        "california",
        "colorado",
        "idaho",
        "montana",
        "nevada",
        "new-mexico",
        "oregon",
        "utah",
        "washington",
        "wyoming",
    ],
    "west": [
        "arizona",
        "california",
        "colorado",
        "idaho",
        "montana",
        "nevada",
        "new-mexico",
        "oregon",
        "utah",
        "washington",
        "wyoming",
    ],
    "us-midwest": [
        "illinois",
        "indiana",
        "iowa",
        "kansas",
        "michigan",
        "minnesota",
        "missouri",
        "nebraska",
        "north-dakota",
        "ohio",
        "south-dakota",
        "wisconsin",
    ],
    "us_midwest": [
        "illinois",
        "indiana",
        "iowa",
        "kansas",
        "michigan",
        "minnesota",
        "missouri",
        "nebraska",
        "north-dakota",
        "ohio",
        "south-dakota",
        "wisconsin",
    ],
    "midwest": [
        "illinois",
        "indiana",
        "iowa",
        "kansas",
        "michigan",
        "minnesota",
        "missouri",
        "nebraska",
        "north-dakota",
        "ohio",
        "south-dakota",
        "wisconsin",
    ],
    "us-south": [
        "alabama",
        "arkansas",
        "delaware",
        "district-of-columbia",
        "florida",
        "georgia",
        "kentucky",
        "louisiana",
        "maryland",
        "mississippi",
        "north-carolina",
        "oklahoma",
        "south-carolina",
        "tennessee",
        "texas",
        "virginia",
        "west-virginia",
    ],
    "us_south": [
        "alabama",
        "arkansas",
        "delaware",
        "district-of-columbia",
        "florida",
        "georgia",
        "kentucky",
        "louisiana",
        "maryland",
        "mississippi",
        "north-carolina",
        "oklahoma",
        "south-carolina",
        "tennessee",
        "texas",
        "virginia",
        "west-virginia",
    ],
    "south": [
        "alabama",
        "arkansas",
        "delaware",
        "district-of-columbia",
        "florida",
        "georgia",
        "kentucky",
        "louisiana",
        "maryland",
        "mississippi",
        "north-carolina",
        "oklahoma",
        "south-carolina",
        "tennessee",
        "texas",
        "virginia",
        "west-virginia",
    ],
}
