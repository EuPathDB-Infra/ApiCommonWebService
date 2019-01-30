#!/usr/bin/perl                                                                                                                     
use strict;
use lib "$ENV{GUS_HOME}/lib/perl";
use DBI;
use DBD::Oracle;
use File::Temp qw/ tempfile /;
use Data::Dumper;


my $idSql = $ARGV[0];
my $useOrthology = $ARGV[1];
$useOrthology =~ s/\'//g;
my $FC = $ARGV[2];

# read the db connection information
my $dbConnection = $ARGV[3];
my $dbLogin = $ARGV[4];
my $dbPassword = $ARGV[5];

# setup DBI connections
my $dbh = DBI->connect($dbConnection, $dbLogin, $dbPassword) or die "Unable to connect: DBI->errstr\n";

########################################################## userlist hash table ##################################################

my $idT = (lc($useOrthology) eq "yes") ? "orthomcl_name" : "source_id";

my $userGeneListQuery = "select distinct ga." . $idT . ", ga.organism from apidbtuning.geneattributes  ga, (" . $idSql . ") id where id.gene_source_id = ga.source_id";

#print STDERR $userGeneListQuery . "\n";

my $userStatemnetHandle = $dbh->prepare($userGeneListQuery);

$userStatemnetHandle->execute();

my %userLists;


while(my ($id,$org) = $userStatemnetHandle->fetchrow_array() ) {

    $userLists{$org}->{$id}++;
}

$userStatemnetHandle->finish();

########################################################## user dataset list hash table ###########################################

my $datasetGeneListQuery = "select distinct ga." . $idT . ", ga.organism, ga.dataset_presenter_id from apidbtuning.datasetgenelist ga where ga.fdiff_abs > $FC";

my $datasetStatmentHandle = $dbh->prepare($datasetGeneListQuery);                                                                   
$datasetStatmentHandle->execute();

my %datasetLists;                                                                                                                  

while(my($id, $org, $dataset) = $datasetStatmentHandle->fetchrow_array() ) {                                             

    $datasetLists{$org}->{$dataset}->{$id}++;                                                                                      

}                                                                                                                                  $datasetStatmentHandle->finish();  

########################################################## Background Dataset list hash table #####################################

my $backgroundDatasetQuery = "select distinct ga." . $idT . ", ga.dataset_presenter_id from apidbtuning.datasetgenelist ga";

my $backgroundDatasetStatmentHandle = $dbh->prepare($backgroundDatasetQuery);                                                                   
$backgroundDatasetStatmentHandle->execute();

my %backgroundDSLists;                                                                                                                  

while(my($id,$dataset) = $backgroundDatasetStatmentHandle->fetchrow_array() ) {                                             

    $backgroundDSLists{$dataset}->{$id}++;                                                                                      

}                                                                                                                                  $backgroundDatasetStatmentHandle->finish();  

######################################################### combined calculation ### ###############################################

#print "dataset_id", "\t", "overlap","\t", "ul_nonDS","\t", "ds_nonUL". "\t", "nonUL_nonDS", "\t", "p_value", "\n";

foreach my $org (keys %userLists){

    my @userIdList = keys %{$userLists{$org}}; 
    my $userListSize = scalar @userIdList;   ############################### user_list SIZE

    #make a hash from the userIdList Array, keys are the elements from @userIdList
    my %userIdListHash = map { $_ => 1 } @userIdList;

    foreach my $dataset (keys %{$datasetLists{$org}}){

	my $backgroundSize = &getBackgroundForOrganism($dbh, $dataset, $idT); ########## background dataset SIZE 

	my  @datasetIdList = keys %{$datasetLists{$org}->{$dataset}};

	my $datasetListSize  =  scalar @datasetIdList; ####################### User_dataset_list SIZE
	
	##### background dataset geneIDs array (this is used for the calculation of $t21)
	my  @backgroundDatasetIdList = keys %{$backgroundDSLists{$dataset}};
	
	my $t11 = 0; 
	my $t21 = 0; 
	my $t12 = 0; 
	my $t22 = 0;
	my $tt = 0;

	# Find the number of overlap between two lists
	foreach my $value1 (@datasetIdList){
          if($userIdListHash{$value1}) {
            $t11++;
	  }
	}

	$t12 = $datasetListSize - $t11;

	foreach my $value2 (@backgroundDatasetIdList){
	    if($userIdListHash{$value2}){
		$tt++;
	    }
	}

	$t21 = $tt - $t11;
	$t22 = $backgroundSize - $t11 - $t12 -$t21;

	my $pValue = &runRscript($t11, $t21, $t12, $t22);


###############################################################################################################
##### the 'print' values order should match up the headers order in ListComparionPlugin.java
###############################################################################################################
 print  $dataset,"\t",$t11,"\t",$t21, "\t", $t12, "\t", $t22, "\t", $pValue, "\n";

    }
}

sub runRscript{
    my ($t11,$t21,$t12,$t22) = @_;
    
    my $rCode = <<"RCODE";
    Test <-matrix(c($t11,$t21,$t12,$t22), nrow = 2, dimnames = list(DS = c("DS", "Non-DS"),UL = c("UL", "Non-UL")))
    fisher.test(Test, alternative = "greater")\$p
RCODE

    my ($FH, $file) = tempfile(SUFFIX => '.R');     
    print $FH  $rCode;
    my $command = "Rscript " .  $file;
    my $p_value  =  `$command`;
    
    #print STDERR $p_value;
    close (FH);
    
    if ($p_value =~ m/^\[1\]\s*([\d]+.+)/ || $p_value =~ m/^\[1\]\s*([\d]+)/) {
        #print "$1\n";
	return  $1;
    }
}


sub getBackgroundForOrganism{
    my ($dbh, $dataset, $idType) = @_;

    my $sql = <<EOSQL;
     select count(distinct $idType) 
     from  apidbtuning.datasetgenelist  
     where dataset_presenter_id = '$dataset'
EOSQL
    
    my $sth = $dbh->prepare($sql);
    $sth->execute();

    my $idCount = $sth->fetchrow_array();
    
    return $idCount;
}


1;
